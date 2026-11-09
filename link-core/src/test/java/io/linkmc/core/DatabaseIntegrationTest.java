package io.linkmc.core;

import io.linkmc.api.LinkApi.*;
import org.junit.jupiter.api.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.linkmc.core.Database.*;

/** Run against a disposable PostgreSQL database, never a production database. */
class DatabaseIntegrationTest {
    Database db;
    EventStore events;
    SessionStore sessions;
    NetworkStore network;
    @BeforeEach void setup() throws Exception {
        String url=System.getenv("LINK_TEST_JDBC_URL");
        Assumptions.assumeTrue(url!=null,"Set LINK_TEST_JDBC_URL for PostgreSQL integration tests");
        db=new Database(() -> DriverManager.getConnection(url,System.getenv("LINK_TEST_DB_USER"),System.getenv("LINK_TEST_DB_PASSWORD")));
        db.migrate();
        db.transaction(c -> {for(String table:"link_route_receipts,link_group_requests,link_group_worlds,link_warp_groups,link_template_channels,link_teleport_requests,link_player_names,link_routes,link_arrivals,link_reservations,link_channels,link_nodes,link_transfers,link_sessions,link_outbox,link_events,link_state,link_operations,link_templates,link_warps".split(","))update(c,"DELETE FROM "+table);return null;});
        events=new EventStore(db);sessions=new SessionStore(db,30);network=new NetworkStore(db);
    }
    @Test void maximumCheckpointUsesBoundedAdvisoryLocksAndRollsBackLateConflict()throws Exception{
        List<Change> initial=new ArrayList<>();
        for(int i=0;i<65536;i++)initial.add(new Change(String.format("large:%05d",i),0,1,"test",new byte[]{1}));
        var first=new Transaction(UUID.randomUUID(),List.of(),initial);
        db.transaction(c->{
            events.commit(c,first);
            if(!maria(c))try(var q=sql(c,"SELECT count(*) FROM pg_locks WHERE pid=pg_backend_pid() AND locktype='advisory'");var r=q.executeQuery()){
                assertTrue(r.next());assertEquals(1,r.getInt(1),"Only operation lock; no per-key advisory locks");
            }
            return null;
        });
        List<Change> updated=new ArrayList<>();
        for(Change change:initial)updated.add(new Change(change.id(),1,1,"test",new byte[]{2}));
        var second=new Transaction(UUID.randomUUID(),List.of(),updated);events.commit(second);
        assertTrue(events.commit(second).duplicate());
        UUID failed=UUID.randomUUID();
        assertThrows(ConflictException.class,()->events.commit(new Transaction(failed,List.of(),List.of(
            new Change("large:00000",2,1,"test",new byte[]{3}),new Change("large:65535",1,1,"test",new byte[]{3})))));
        assertEquals(2,events.read("large:00000").orElseThrow().version());
        assertArrayEquals(new byte[]{2},events.read("large:00000").orElseThrow().payload());
        db.transaction(c->{try(var q=sql(c,"SELECT count(*) FROM link_operations WHERE id=?",failed);var r=q.executeQuery()){assertTrue(r.next());assertEquals(0,r.getInt(1));}return null;});
    }
    @Test void unguardedConcurrentWritersCannotLoseUpdates()throws Exception{
        try(var pool=Executors.newFixedThreadPool(8)){
            for(long version=0;version<2;version++){
                final long expected=version;var start=new CountDownLatch(1);List<Future<Boolean>> attempts=new ArrayList<>();
                for(int i=0;i<8;i++)attempts.add(pool.submit(()->{start.await();try{
                    events.commit(new Transaction(UUID.randomUUID(),List.of(),List.of(new Change("race",expected,1,"test",new byte[]{1}))));return true;
                }catch(ConflictException conflict){return false;}}));
                start.countDown();int wins=0;for(var attempt:attempts)if(attempt.get(10,TimeUnit.SECONDS))wins++;assertEquals(1,wins);
            }
        }
        assertEquals(2,events.read("race").orElseThrow().version());
    }
    private Destination travelSetup(String node,String channel)throws Exception{
        var d=new Destination(node,channel,8,66,8,90,0);network.heartbeat(node,100);network.register(channel,d,50);return d;
    }
    @Test void localTravelKeepsEpochAndDuplicateRequestsReturnOriginalReceipt()throws Exception{
        var d=travelSetup("a","spawn-a");var owner=sessions.acquire(UUID.randomUUID(),"a");
        var routes=new RouteStore(db);UUID operation=UUID.randomUUID();var reservation=routes.requestTravel(owner.player(),operation,d);
        var request=new RouteStore.Request(operation,reservation);assertTrue(routes.claim(request,owner));assertFalse(routes.claim(request,owner));
        assertEquals(owner.epoch(),sessions.renew(owner).epoch());
        assertEquals(d,routes.completeLocal(request,owner));assertEquals(d,routes.completeLocal(request,owner));assertFalse(routes.claim(request,owner));
        assertEquals(owner.epoch(),sessions.renew(owner).epoch());assertEquals("COMPLETE",routes.status(operation));
        assertEquals(reservation,routes.requestTravel(owner.player(),operation,d));
        assertTrue(sessions.disconnect(owner));var next=sessions.acquire(owner.player(),"a");
        assertEquals(reservation,routes.requestTravel(owner.player(),operation,d));assertFalse(routes.claim(request,next));
        assertThrows(ConflictException.class,()->routes.requestTravel(owner.player(),operation,new Destination("a","spawn-a",9,66,8,0,0)));
        db.transaction(c->{try(var q=sql(c,"SELECT count(*) FROM link_transfers WHERE player=?",owner.player());var r=q.executeQuery()){r.next();assertEquals(0,r.getInt(1));}return null;});
    }
    @Test void remoteTravelPrepareIsAtomicAndAcceptedEpochSurvivesSourceDisconnect()throws Exception{
        var d=travelSetup("b","spawn-b");var owner=sessions.acquire(UUID.randomUUID(),"a");var routes=new RouteStore(db);UUID id=UUID.randomUUID();
        var reservation=routes.requestTravel(owner.player(),id,d);var request=new RouteStore.Request(id,reservation);
        assertTrue(routes.claim(request,owner));routes.prepareRemote(request,owner);routes.prepareRemote(request,owner);assertTrue(sessions.renewMoving(owner));routes.ready(id);
        var arrived=new ArrivalStore(db).claim(owner.player(),"b");assertEquals(owner.epoch()+1,arrived.session().epoch());
        assertFalse(sessions.disconnect(owner));assertFalse(sessions.renewMoving(owner));assertEquals(arrived.session().epoch(),sessions.renew(arrived.session()).epoch());
        routes.finish(id,true);assertEquals(reservation,routes.requestTravel(owner.player(),id,d));assertFalse(routes.claim(request,arrived.session()));
        assertThrows(ConflictException.class,()->new ArrivalStore(db).claim(owner.player(),"b"));
    }
    @Test void failedRemoteIssueRollsBackOwnershipAndDisconnectCancelsPendingTransfer()throws Exception{
        var d=travelSetup("b","spawn-b");var owner=sessions.acquire(UUID.randomUUID(),"a");var routes=new RouteStore(db);UUID id=UUID.randomUUID();
        var request=new RouteStore.Request(id,routes.requestTravel(owner.player(),id,d));assertTrue(routes.claim(request,owner));
        network.ready("spawn-b",false);assertThrows(ConflictException.class,()->routes.prepareRemote(request,owner));
        assertEquals(owner.epoch(),sessions.renew(owner).epoch());network.ready("spawn-b",true);
        routes.prepareRemote(request,owner);assertFalse(sessions.available(owner.player()));assertTrue(sessions.disconnect(owner));assertTrue(sessions.available(owner.player()));
        assertEquals("FAILED",routes.status(id));assertThrows(ConflictException.class,()->new ArrivalStore(db).claim(owner.player(),"b"));
        assertEquals(owner.epoch()+1,sessions.acquire(owner.player(),"a").epoch());
    }
    @Test void concurrentRouteClaimOnlyOneWinsAndExpiredRouteFailsWithoutChangingOwner()throws Exception{
        var d=travelSetup("a","spawn-a");var owner=sessions.acquire(UUID.randomUUID(),"a");var routes=new RouteStore(db);UUID id=UUID.randomUUID();
        var request=new RouteStore.Request(id,routes.requestTravel(owner.player(),id,d));
        try(var pool=Executors.newFixedThreadPool(8)){
            List<Future<Boolean>> claims=new ArrayList<>();for(int i=0;i<8;i++)claims.add(pool.submit(()->routes.claim(request,owner)));
            int wins=0;for(var result:claims)if(result.get(10,TimeUnit.SECONDS))wins++;assertEquals(1,wins);
        }
        routes.reject(request);assertEquals(owner.epoch(),sessions.renew(owner).epoch());
        UUID stale=UUID.randomUUID();var staleRequest=new RouteStore.Request(stale,routes.requestTravel(owner.player(),stale,d));
        db.transaction(c->{update(c,"UPDATE link_reservations SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",stale);return null;});
        assertThrows(ConflictException.class,()->routes.claim(staleRequest,owner));routes.reject(staleRequest);
        assertEquals("FAILED",routes.status(stale));assertEquals(owner.epoch(),sessions.renew(owner).epoch());
    }
    @Test void crashedSourcePinnedReservationIsReapedBeforeReconnect()throws Exception{
        var d=travelSetup("a","spawn-a");var owner=sessions.acquire(UUID.randomUUID(),"a");var routes=new RouteStore(db);UUID id=UUID.randomUUID();
        var request=new RouteStore.Request(id,routes.requestTravel(owner.player(),id,d));assertTrue(routes.claim(request,owner));
        assertFalse(sessions.available(owner.player()));
        db.transaction(c->{update(c,"UPDATE link_sessions SET expires_at=clock_timestamp()-interval '1 second' WHERE player=?",owner.player());return null;});
        assertTrue(sessions.available(owner.player()));assertEquals("FAILED",routes.status(id));
        UUID ticket=UUID.randomUUID();var reservation=network.reserve(owner.player(),ticket,d);new ArrivalStore(db).issue(ticket,reservation,null);
        assertFalse(sessions.disconnect(owner));var arrived=new ArrivalStore(db).claim(owner.player(),"a");assertEquals(owner.epoch()+1,arrived.session().epoch());
    }
    private Database independentDatabase(){return new Database(()->DriverManager.getConnection(System.getenv("LINK_TEST_JDBC_URL"),System.getenv("LINK_TEST_DB_USER"),System.getenv("LINK_TEST_DB_PASSWORD")));}
    @Test void checkpointGuardDoesNotBlockLeaseRenewal()throws Exception{
        var owner=sessions.acquireNode("node-a");var locked=new CountDownLatch(1);var finish=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var checkpoint=pool.submit(()->db.transaction(c->{SessionStore.requireActive(c,owner);locked.countDown();assertTrue(finish.await(8,TimeUnit.SECONDS));return null;}));
            try{assertTrue(locked.await(2,TimeUnit.SECONDS));var renewed=pool.submit(()->sessions.renew(owner));
                assertEquals(owner.epoch(),renewed.get(2,TimeUnit.SECONDS).epoch());
                var takeover=pool.submit(()->sessions.acquireNode("node-a"));
                assertThrows(TimeoutException.class,()->takeover.get(200,TimeUnit.MILLISECONDS));
                finish.countDown();checkpoint.get();takeover.get(5,TimeUnit.SECONDS);
            }finally{finish.countDown();}
        }
    }
    @Test void renewalRetriesTemporaryRowLockTimeout()throws Exception{
        var owner=sessions.acquireNode("node-a");var locked=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()){
            var blocker=pool.submit(()->db.transaction(c->{try(var q=sql(c,"SELECT player FROM link_sessions WHERE player=? FOR UPDATE",owner.player());var r=q.executeQuery()){assertTrue(r.next());locked.countDown();Thread.sleep(5600);}return null;}));
            assertTrue(locked.await(2,TimeUnit.SECONDS));assertEquals(owner.epoch(),sessions.renew(owner).epoch());blocker.get();
        }
    }
    @Test void renewalCannotReviveLeaseThatExpiresWhileWaitingForRow()throws Exception{
        var owner=sessions.acquireNode("node-a");
        db.transaction(c->{update(c,"UPDATE link_sessions SET expires_at=clock_timestamp()+?*interval '1 second' WHERE player=?",1,owner.player());return null;});
        var locked=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()){
            var blocker=pool.submit(()->db.transaction(c->{try(var q=sql(c,"SELECT player FROM link_sessions WHERE player=? FOR UPDATE",owner.player());var r=q.executeQuery()){assertTrue(r.next());locked.countDown();Thread.sleep(1800);}return null;}));
            assertTrue(locked.await(2,TimeUnit.SECONDS));assertThrows(ConflictException.class,()->sessions.renew(owner));blocker.get();
        }
    }
    @Test void renewalNeverRevivesExpiredLease()throws Exception{
        var owner=sessions.acquireNode("node-a");db.transaction(c->{update(c,"UPDATE link_sessions SET expires_at=clock_timestamp()-interval '1 second' WHERE player=?",owner.player());return null;});
        assertThrows(ConflictException.class,()->sessions.renew(owner));
    }
    @Test void nodeTakeoverRevokesOldPlayersButPreservesOtherNodes()throws Exception{
        var first=sessions.acquireNode("node-a");var player=sessions.acquire(UUID.randomUUID(),"node-a");
        var other=sessions.acquire(UUID.randomUUID(),"node-b");
        var moving=sessions.acquire(UUID.randomUUID(),"node-a");
        var transfer=sessions.prepare(moving,UUID.randomUUID(),new Destination("node-b","world",0,65,0,0,0));
        var second=sessions.acquireNode("node-a");assertEquals(first.epoch()+1,second.epoch());
        assertThrows(ConflictException.class,()->sessions.renew(first));
        assertThrows(ConflictException.class,()->sessions.renew(player));
        assertThrows(ConflictException.class,()->sessions.accept(moving.player(),transfer.id(),"node-b"));
        assertFalse(sessions.releaseNode(first));assertEquals(other.epoch(),sessions.renew(other).epoch());
        assertTrue(sessions.releaseNode(second));assertFalse(sessions.releaseNode(second));
        assertEquals(second.epoch()+1,sessions.acquireNode("node-a").epoch());
    }
    @Test void boundBackendCanReleaseAndImmediatelyRestart()throws Exception{
        var owner=sessions.acquireNode("node-a");
        try(var backend=independentDatabase()){
            backend.nodeOwner(()->owner);assertTrue(new SessionStore(backend,30).releaseNode(owner));
            assertThrows(ConflictException.class,()->backend.transaction(c->1));
            assertEquals(owner.epoch()+1,sessions.acquireNode("node-a").epoch());
        }
    }
    @Test void concurrentNodeStartupsPublishDistinctEpochs()throws Exception{
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);
            Callable<Session> acquire=()->{start.await();return sessions.acquireNode("node-a");};
            var one=pool.submit(acquire);var two=pool.submit(acquire);start.countDown();
            var a=one.get(10,TimeUnit.SECONDS);var b=two.get(10,TimeUnit.SECONDS);
            assertNotEquals(a.epoch(),b.epoch());var old=a.epoch()<b.epoch()?a:b;var current=a.epoch()>b.epoch()?a:b;
            assertThrows(ConflictException.class,()->sessions.renew(old));assertEquals(current.epoch(),sessions.renew(current).epoch());
        }
    }
    @Test void backendGuardFencesAllTransactionsAndStaleShutdown()throws Exception{
        var first=sessions.acquireNode("node-a");
        try(var old=independentDatabase()){
            old.nodeOwner(()->first);assertEquals(1,old.<Integer>transaction(c->1).intValue());
            var second=sessions.acquireNode("node-a");
            assertThrows(ConflictException.class,()->new NetworkStore(old).heartbeat("node-a",100));
            assertThrows(ConflictException.class,()->new SessionStore(old,30).releaseNode(first));
            assertEquals(second.epoch(),sessions.renew(second).epoch());
        }
    }
    @Test void takeoverWaitsForInFlightWritesWhileReadersRemainConcurrent()throws Exception{
        var first=sessions.acquireNode("node-a");
        try(var old=independentDatabase();var pool=Executors.newFixedThreadPool(3)){
            old.nodeOwner(()->first);var entered=new CountDownLatch(2);var finish=new CountDownLatch(1);
            Callable<Integer> work=()->old.transaction(c->{entered.countDown();assertTrue(finish.await(4,TimeUnit.SECONDS));return 1;});
            var one=pool.submit(work);var two=pool.submit(work);
            try{
                assertTrue(entered.await(3,TimeUnit.SECONDS),"Backend transactions must overlap");
                var takeover=pool.submit(()->sessions.acquireNode("node-a"));
                assertThrows(TimeoutException.class,()->takeover.get(200,TimeUnit.MILLISECONDS));
                finish.countDown();assertEquals(1,one.get());assertEquals(1,two.get());
                assertEquals(first.epoch()+1,takeover.get(5,TimeUnit.SECONDS).epoch());
                assertThrows(ConflictException.class,()->old.transaction(c->1));
            }finally{finish.countDown();}
        }
    }
    @Test void provisionedSpawnUsesTemplatePositionAndView()throws Exception{
        network.heartbeat("node-a",100);
        Worlds worlds=(Worlds)java.lang.reflect.Proxy.newProxyInstance(Worlds.class.getClassLoader(),new Class[]{Worlds.class},(proxy,method,args)->switch(method.getName()){
            case "create"->CompletableFuture.completedFuture(new WorldInfo((String)args[0],false,0));
            case "spawn"->CompletableFuture.completedFuture(new Destination("node-a",(String)args[0],2.25,65,3.75,123,17));
            default->throw new UnsupportedOperationException(method.getName());
        });
        assertTrue(new SpawnProvisioner(worlds,network,"node-a","lobby",1,50,3).ensureSpare().toCompletableFuture().get(10,TimeUnit.SECONDS));
        var channel=network.spawnChannels().getFirst();
        var reservation=network.reserve(UUID.randomUUID(),UUID.randomUUID());
        assertEquals(2.25,reservation.destination().x());assertEquals(123,reservation.destination().yaw());assertEquals(17,reservation.destination().pitch());
        assertEquals(channel.world(),reservation.destination().world());
    }
    @Test void groupPolicyImmutableAndFixedWarpNamesCannotCollide()throws Exception{
        var templates=new TemplateStore(db);var p=new ChannelPolicy(1,2,true,true);templates.publish("mine",1,new byte[]{1,2},p);
        assertThrows(ConflictException.class,()->templates.publish("mine",1,new byte[]{1,2},ChannelPolicy.defaults()));
        var groups=new WarpGroupStore(db);groups.bind("mines","mine",1,"node-a");assertEquals(p,groups.list().getFirst().policy());
        assertThrows(ConflictException.class,()->network.setWarp("mines",new Destination("node-a","world",0,65,0,0,0)));
        assertTrue(network.warps().contains("mines"));assertTrue(network.deleteWarp("mines"));assertTrue(groups.list().isEmpty());
    }
    @Test void groupCreatesAcrossNodesAndRespectsCapacityAndMaximum()throws Exception{
        var groups=new WarpGroupStore(db);new TemplateStore(db).publish("mine",1,new byte[]{1},new ChannelPolicy(1,2,true,true));
        network.heartbeat("node-a",10);network.heartbeat("node-b",10);groups.bind("mines","mine",1,"node-a");
        Session nodeA=sessions.acquire(UUID.randomUUID(),"node-a"),nodeB=sessions.acquire(UUID.randomUUID(),"node-b");
        Session a=sessions.acquire(UUID.randomUUID(),"node-a"),b=sessions.acquire(UUID.randomUUID(),"node-a"),c=sessions.acquire(UUID.randomUUID(),"node-a");
        UUID one=UUID.randomUUID(),two=UUID.randomUUID(),three=UUID.randomUUID();groups.enqueue(a.player(),one,"mines");groups.provisionDemand();
        var first=groups.jobs("node-a").getFirst();groups.ready(first,new Destination("node-a",first.world(),7.25,65,8.75,90,12),nodeA);
        var route=groups.dispatch(groups.pending("node-a").getFirst()).orElseThrow();assertEquals(7.25,route.reservation().destination().x());assertEquals("DISPATCHED",groups.status(one));
        assertTrue(network.spawnChannels().isEmpty());assertThrows(ConflictException.class,()->network.reserve(UUID.randomUUID(),UUID.randomUUID()));
        groups.enqueue(b.player(),two,"mines");groups.provisionDemand();var second=groups.jobs("node-b").getFirst();groups.ready(second,new Destination("node-b",second.world(),7.25,65,8.75,90,12),nodeB);
        assertEquals("node-b",groups.dispatch(groups.pending("node-a").getFirst()).orElseThrow().reservation().destination().node());
        groups.enqueue(c.player(),three,"mines");assertTrue(groups.dispatch(groups.pending("node-a").getFirst()).isEmpty());groups.provisionDemand();assertTrue(groups.jobs("node-a").isEmpty());assertTrue(groups.jobs("node-b").isEmpty());
        assertEquals(2,network.channels().size());assertEquals("WAITING",groups.status(three));
    }
    @Test void concurrentDemandOnlyCreatesOneJobAndLocalPolicyDoesNotUseOtherNodes()throws Exception{
        var groups=new WarpGroupStore(db);new TemplateStore(db).publish("mine",1,new byte[]{1},new ChannelPolicy(1,3,true,false));
        network.heartbeat("node-a",20);network.heartbeat("node-b",20);groups.bind("mines","mine",1,"node-a");
        for(int i=0;i<8;i++){var p=sessions.acquire(UUID.randomUUID(),"node-b");groups.enqueue(p.player(),UUID.randomUUID(),"mines");}
        try(var pool=Executors.newFixedThreadPool(8)){var jobs=new ArrayList<Future<?>>();for(int i=0;i<8;i++)jobs.add(pool.submit(()->{try{groups.provisionDemand();}catch(Exception e){throw new RuntimeException(e);}}));for(var f:jobs)f.get();}
        assertEquals(1,groups.jobs("node-a").size());assertTrue(groups.jobs("node-b").isEmpty());
    }
    @Test void groupWaitExpiryAndSessionChangesNeverDispatch()throws Exception{
        var groups=new WarpGroupStore(db);new TemplateStore(db).publish("mine",1,new byte[]{1});groups.bind("mines","mine",1,"node-a");
        Session p=sessions.acquire(UUID.randomUUID(),"node-a");UUID id=UUID.randomUUID();groups.enqueue(p.player(),id,"mines");
        groups.enqueue(p.player(),id,"mines");assertEquals(1,groups.pending("node-a").size());
        assertThrows(ConflictException.class,()->groups.enqueue(p.player(),UUID.randomUUID(),"mines"));
        sessions.release(p);sessions.acquire(p.player(),"node-a");assertTrue(groups.dispatch(groups.pending("node-a").getFirst()).isEmpty());assertEquals("FAILED",groups.status(id));
        UUID expired=UUID.randomUUID();groups.enqueue(p.player(),expired,"mines");db.transaction(c->{update(c,"UPDATE link_group_requests SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",expired);return null;});
        assertTrue(groups.dispatch(groups.pending("node-a").getFirst()).isEmpty());assertEquals("FAILED",groups.status(expired));assertEquals("MISSING",new RouteStore(db).status(expired));
    }
    @Test void disabledAutoExpansionKeepsSingleChannel()throws Exception{
        var groups=new WarpGroupStore(db);new TemplateStore(db).publish("mine",1,new byte[]{1},new ChannelPolicy(1,3,false,false));network.heartbeat("node-a",10);groups.bind("mines","mine",1,"node-a");
        var node=sessions.acquire(UUID.randomUUID(),"node-a");var a=sessions.acquire(UUID.randomUUID(),"node-a");groups.enqueue(a.player(),UUID.randomUUID(),"mines");groups.provisionDemand();var job=groups.jobs("node-a").getFirst();groups.ready(job,new Destination("node-a",job.world(),0,65,0,0,0),node);groups.dispatch(groups.pending("node-a").getFirst());
        var b=sessions.acquire(UUID.randomUUID(),"node-a");groups.enqueue(b.player(),UUID.randomUUID(),"mines");groups.provisionDemand();assertTrue(groups.jobs("node-a").isEmpty());
    }
    @Test void groupDispatchAndOutboxFailureRollBackReservationAndRoute()throws Exception{
        var groups=new WarpGroupStore(db);new TemplateStore(db).publish("mine",1,new byte[]{1},new ChannelPolicy(1,1,true,false));network.heartbeat("node-a",10);groups.bind("mines","mine",1,"node-a");
        var owner=sessions.acquire(UUID.randomUUID(),"node-a");var player=sessions.acquire(UUID.randomUUID(),"node-a");UUID id=UUID.randomUUID();groups.enqueue(player.player(),id,"mines");groups.provisionDemand();var job=groups.jobs("node-a").getFirst();groups.ready(job,new Destination("node-a",job.world(),1,65,2,0,0),owner);
        UUID operation=UUID.nameUUIDFromBytes(("group:"+id+":DISPATCHED").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        db.transaction(c->{update(c,"ALTER TABLE link_outbox ADD CONSTRAINT test_group_reject CHECK (id <> '"+operation+"')");return null;});
        try{assertThrows(Exception.class,()->groups.dispatch(groups.pending("node-a").getFirst()));assertEquals("WAITING",groups.status(id));assertEquals("MISSING",new RouteStore(db).status(id));assertEquals(0,network.channels().getFirst().reserved());}
        finally{db.transaction(c->{update(c,"ALTER TABLE link_outbox DROP CONSTRAINT test_group_reject");return null;});}
        assertTrue(groups.dispatch(groups.pending("node-a").getFirst()).isPresent());assertEquals(1,network.channels().getFirst().reserved());
    }
    @Test void groupRequestsRetainIdempotencyAfterPruningAndDeletedNamesStayRetired()throws Exception{
        var groups=new WarpGroupStore(db);new TemplateStore(db).publish("mine",1,new byte[]{1});groups.bind("mines","mine",1,"node-a");var p=sessions.acquire(UUID.randomUUID(),"node-a");UUID id=UUID.randomUUID();groups.enqueue(p.player(),id,"mines");
        db.transaction(c->{update(c,"UPDATE link_group_requests SET expires_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(259200)),id);return null;});
        assertEquals(1,new EventRetention(db).prune(24,100).requests());assertThrows(ConflictException.class,()->groups.enqueue(p.player(),id,"mines"));
        assertTrue(network.deleteWarp("mines"));assertThrows(ConflictException.class,()->groups.bind("mines","mine",1,"node-a"));
    }
    private Transaction change(Session s,UUID op,long expected,String value) {
        return new Transaction(op,List.of(s),List.of(new Change("player:"+s.player(),expected,1,"test:set",value.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
    @Test void latestStateCommitWorksWhenEventInsertsAreForbidden()throws Exception{
        db.transaction(c->{update(c,"ALTER TABLE link_events ADD CONSTRAINT test_no_history CHECK (version < 0)");return null;});
        try{
            var owner=sessions.acquire(UUID.randomUUID(),"node-a");var tx=change(owner,UUID.randomUUID(),0,"current");
            events.commit(tx);assertTrue(events.commit(tx).duplicate());
            assertArrayEquals("current".getBytes(),new EventStore(db).read("player:"+owner.player()).orElseThrow().payload());
            assertTrue(events.history("player:"+owner.player(),0,100).isEmpty());
            age(tx.operation());assertEquals(0,new EventRetention(db).prune(24,100).operations());
        }finally{db.transaction(c->{update(c,"ALTER TABLE link_events DROP CONSTRAINT test_no_history");return null;});}
    }
    private void age(UUID operation)throws Exception{
        db.transaction(c->{update(c,"UPDATE link_operations SET created_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofDays(3))),operation);return null;});
    }
    private Commit legacyCommit(Transaction tx)throws Exception{
        Commit result=events.commit(tx);
        db.transaction(c->{
            update(c,"UPDATE link_operations SET events_pruned=false WHERE id=?",tx.operation());
            for(Change change:tx.changes())update(c,"INSERT INTO link_events(aggregate_id,version,operation,schema_version,type,payload) VALUES (?,?,?,?,?,?)",change.id(),change.expectedVersion()+1,tx.operation(),change.schema(),change.type(),change.after());
            return null;
        });
        return result;
    }
    @Test void retentionPreservesStateIdempotencyAndPendingNotifications()throws Exception{
        Session owner=sessions.acquire(UUID.randomUUID(),"a");String key="player:"+owner.player();
        Transaction first=change(owner,UUID.randomUUID(),0,"first");legacyCommit(first);age(first.operation());
        Transaction second=change(owner,UUID.randomUUID(),1,"second");legacyCommit(second);age(second.operation());
        Transaction latest=change(owner,UUID.randomUUID(),2,"latest");legacyCommit(latest);
        db.transaction(c->{update(c,"UPDATE link_outbox SET delivered=true WHERE id=?",first.operation());update(c,"UPDATE link_outbox SET delivered=true WHERE id=?",latest.operation());return null;});
        var retention=new EventRetention(db);var result=retention.prune(24,1);
        assertEquals(1,result.operations());assertEquals(1,result.events());assertEquals(1,result.notifications());
        assertTrue(events.commit(first).duplicate());assertEquals(3,events.read(key).orElseThrow().version());
        assertThrows(ConflictException.class,()->events.commit(change(owner,first.operation(),0,"different")));
        assertEquals(1,retention.prune(24,100).events());assertEquals(0,retention.prune(24,100).events());
        assertEquals(List.of(3L),events.history(key,0,100).stream().map(e->e.state().version()).toList());
        assertTrue(events.replay(key,2).isEmpty());assertArrayEquals("latest".getBytes(),events.replay(key,3).orElseThrow().payload());
        int notifications=db.transaction(c->{try(var s=sql(c,"SELECT count(*) FROM link_outbox");var r=s.executeQuery()){r.next();return r.getInt(1);}});
        assertEquals(2,notifications);
        var restored=new FrameCommitter(tx->{throw new AssertionError("Unchanged recovered state must not write");});
        restored.restore(events.read(key).orElseThrow());restored.commit(List.of(owner),Map.of(key,"latest".getBytes()),()->{});
    }
    @Test void retentionCanRemoveLastLegacyEventWhilePreservingLatestState()throws Exception{
        var original=new Transaction(UUID.randomUUID(),List.of(),List.of(new Change("a",0,1,"test",new byte[]{1}),new Change("b",0,1,"test",new byte[]{2})));
        legacyCommit(original);age(original.operation());
        legacyCommit(new Transaction(UUID.randomUUID(),List.of(),List.of(new Change("a",1,1,"test",new byte[]{3}))));
        assertEquals(2,new EventRetention(db).prune(24,100).events());
        assertEquals(1,events.history("a",0,100).size());assertTrue(events.history("b",0,100).isEmpty());
        assertArrayEquals(new byte[]{2},events.read("b").orElseThrow().payload());
    }
    @Test void concurrentRetentionWorkersDeleteEachOldOperationOnce()throws Exception{
        for(int i=0;i<7;i++){
            UUID operation=UUID.randomUUID();legacyCommit(new Transaction(operation,List.of(),List.of(new Change("a",i,1,"test",new byte[]{(byte)i}))));
            if(i<6)age(operation);
        }
        try(var executor=Executors.newFixedThreadPool(2)){
            var a=executor.submit(()->new EventRetention(db).prune(24,100));var b=executor.submit(()->new EventRetention(db).prune(24,100));
            assertEquals(6,a.get().events()+b.get().events());
        }
        assertEquals(1,events.history("a",0,100).size());assertEquals(7,events.read("a").orElseThrow().version());
    }
    @Test void retentionFailureRollsBackDeletedEvents()throws Exception{
        UUID old=UUID.randomUUID();legacyCommit(new Transaction(old,List.of(),List.of(new Change("a",0,1,"test",new byte[]{1}))));age(old);
        legacyCommit(new Transaction(UUID.randomUUID(),List.of(),List.of(new Change("a",1,1,"test",new byte[]{2}))));
        db.transaction(c->{update(c,"ALTER TABLE link_operations ADD CONSTRAINT test_retention_reject CHECK (NOT events_pruned)");return null;});
        try{assertThrows(Exception.class,()->new EventRetention(db).prune(24,100));assertEquals(2,events.history("a",0,100).size());}
        finally{db.transaction(c->{update(c,"ALTER TABLE link_operations DROP CONSTRAINT test_retention_reject");return null;});}
    }
    @Test void warpLifecycleIsSharedAndStoresLatestState()throws Exception{
        var first=new Destination("a","shop-world",1.5,65,2.5,90,10);
        var second=new Destination("b","mall",3,70,4,-45,20);
        network.setWarp("shop",first);var otherBackend=new NetworkStore(db);
        assertEquals(first,otherBackend.warp("shop").orElseThrow());assertEquals(List.of("shop"),otherBackend.warps());
        otherBackend.setWarp("shop",second);assertEquals(second,network.warp("shop").orElseThrow());
        assertTrue(network.deleteWarp("shop"));assertFalse(otherBackend.deleteWarp("shop"));assertTrue(otherBackend.warps().isEmpty());
        assertTrue(events.history(io.linkmc.api.LinkApi.warpStateId("shop"),0,10).isEmpty());
        assertEquals(3,events.read(io.linkmc.api.LinkApi.warpStateId("shop")).orElseThrow().version());assertEquals(0,events.read(io.linkmc.api.LinkApi.warpStateId("shop")).orElseThrow().payload().length);
    }
    @Test void maximumLengthWarpNameHasBoundedJournalIdentity()throws Exception{
        String name="w".repeat(160);var destination=new Destination("a","shop",0,65,0,0,0);
        network.setWarp(name,destination);assertEquals(destination,network.warp(name).orElseThrow());
        String key=io.linkmc.api.LinkApi.warpStateId(name);assertTrue(key.length()<=160);
        assertEquals(1,events.read(key).orElseThrow().version());assertTrue(network.deleteWarp(name));assertEquals(2,events.read(key).orElseThrow().version());
    }
    @Test void warpOnlyWorldNeverReceivesDefaultSpawnAdmissions()throws Exception{
        network.heartbeat("a",10);var destination=new Destination("a","shop",5,65,6,90,0);
        network.setWarp("shop",destination,2);
        assertThrows(ConflictException.class,()->network.reserve(UUID.randomUUID(),UUID.randomUUID()));
        assertEquals(0,network.channelCount("a"));assertTrue(network.spawnChannels().isEmpty());
        var reserved=network.reserve(UUID.randomUUID(),UUID.randomUUID(),destination);assertEquals(destination,reserved.destination());
        assertThrows(ConflictException.class,()->network.closeWorld("a","shop"));
        network.cancel(reserved.id(),reserved.player());network.closeWorld("a","shop");
        assertThrows(ConflictException.class,()->network.reserve(UUID.randomUUID(),UUID.randomUUID(),destination));
        network.openWarpWorld("a","shop");assertEquals(1,network.channels().size());
        network.syncWarpWorlds("a",List.of());assertTrue(network.channels().isEmpty());
        network.syncWarpWorlds("a",List.of("shop"));assertEquals(1,network.channels().size());
        network.register("a:shop",destination,2);assertTrue(network.spawnChannels().isEmpty());
    }
    @Test void warpChannelProjectionAndEventRollbackTogether()throws Exception{
        network.heartbeat("a",10);
        db.transaction(c->{update(c,"ALTER TABLE link_outbox ADD CONSTRAINT test_warp_reject CHECK (false)");return null;});
        try{
            assertThrows(Exception.class,()->network.setWarp("shop",new Destination("a","shop",0,65,0,0,0),2));
            assertTrue(network.warp("shop").isEmpty());assertTrue(events.read(io.linkmc.api.LinkApi.warpStateId("shop")).isEmpty());assertTrue(network.channels().isEmpty());
        }finally{db.transaction(c->{update(c,"ALTER TABLE link_outbox DROP CONSTRAINT test_warp_reject");return null;});}
        var destination=new Destination("a","shop",0,65,0,0,0);network.setWarp("shop",destination,2);
        db.transaction(c->{update(c,"DELETE FROM link_outbox");update(c,"ALTER TABLE link_outbox ADD CONSTRAINT test_warp_reject CHECK (false)");return null;});
        try{
            assertThrows(Exception.class,()->network.deleteWarp("shop"));
            assertEquals(destination,network.warp("shop").orElseThrow());assertEquals(1,events.read(io.linkmc.api.LinkApi.warpStateId("shop")).orElseThrow().version());
        }finally{db.transaction(c->{update(c,"ALTER TABLE link_outbox DROP CONSTRAINT test_warp_reject");return null;});}
    }
    @Test void concurrentWarpUpdatesHaveOrderedVersions()throws Exception{
        try(var executor=Executors.newFixedThreadPool(4)){
            List<Future<?>> tasks=new ArrayList<>();for(int i=0;i<8;i++){int x=i;tasks.add(executor.submit(()->{network.setWarp("shop",new Destination("a","shop",x,65,0,0,0));return null;}));}
            for(var task:tasks)task.get();
        }
        assertEquals(8,events.read(io.linkmc.api.LinkApi.warpStateId("shop")).orElseThrow().version());assertTrue(events.history(io.linkmc.api.LinkApi.warpStateId("shop"),0,10).isEmpty());
        try(var service=new LinkService(db)){
            var tx=new Transaction(UUID.randomUUID(),List.of(),List.of(new Change(io.linkmc.api.LinkApi.warpStateId("shop"),8,1,"bad",new byte[]{1})));
            assertThrows(ExecutionException.class,()->service.data().commit(tx).toCompletableFuture().get());
        }
    }
    private Session tpPlayer(String name,String node)throws Exception{
        network.heartbeat(node,20);Session owner=sessions.acquire(UUID.randomUUID(),node);new TeleportStore(db).registerPlayer(owner.player(),name);return owner;
    }
    @Test void teleportRequiresConsentAndRoutesRequesterToRecipient()throws Exception{
        var a=tpPlayer("Alice","a");var b=tpPlayer("Bob","b");var store=new TeleportStore(db);
        var request=store.request(a.player(),"bOB",false);
        assertEquals("PENDING",request.state());assertTrue(new RouteStore(db).pending("a").isEmpty());assertEquals(1,store.incoming(b.player()).size());
        assertThrows(TeleportStore.Rejected.class,()->store.resolve(request.id(),new Destination("b","lobby",1,65,2,90,0)));
        assertThrows(TeleportStore.Rejected.class,()->store.request(a.player(),"Bob",false));
        assertThrows(TeleportStore.Rejected.class,()->store.respond(a.player(),"Alice",true));
        assertEquals("ACCEPTED",store.respond(b.player(),null,true).state());
        var work=store.work("b").getFirst();var destination=new Destination("b","lobby",1,65,2,90,0);
        try(var service=new LinkService(db)){
            service.submitTeleport(work,destination,10).toCompletableFuture().get(5,TimeUnit.SECONDS);
            service.submitTeleport(work,destination,10).toCompletableFuture().get(5,TimeUnit.SECONDS);
        }
        var route=new RouteStore(db).pending("a").getFirst();assertEquals(request.id(),route.id());assertEquals(a.player(),route.reservation().player());assertEquals(destination,route.reservation().destination());
        assertEquals("DISPATCHED",store.notices("a").getFirst().state());assertTrue(store.work("b").isEmpty());
        assertTrue(events.history("tpa:"+a.player(),0,10).isEmpty());
        var saved=com.google.gson.JsonParser.parseString(new String(events.read("tpa:"+a.player()).orElseThrow().payload(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("b",saved.getAsJsonObject("destination").get("node").getAsString());assertEquals(a.epoch(),saved.get("requesterEpoch").getAsLong());
    }
    @Test void teleportHereMovesRecipientToRequesterAndRejectsSelfOffline()throws Exception{
        var a=tpPlayer("Alice","a");var b=tpPlayer("Bob","b");var store=new TeleportStore(db);
        assertThrows(TeleportStore.Rejected.class,()->store.request(a.player(),"Alice",false));assertThrows(TeleportStore.Rejected.class,()->store.request(a.player(),"Missing",false));
        var request=store.request(a.player(),"Bob",true);store.respond(b.player(),"Alice",true);
        var destination=new Destination("a","home",8,70,9,0,0);
        try(var service=new LinkService(db)){service.submitTeleport(store.work("a").getFirst(),destination,10).toCompletableFuture().get(5,TimeUnit.SECONDS);}
        var route=new RouteStore(db).pending("b").getFirst();assertEquals(request.id(),route.id());assertEquals(b.player(),route.reservation().player());assertEquals(destination,route.reservation().destination());
    }
    @Test void teleportDenyCancelExpiryAndReconnectNeverAuthorizeTravel()throws Exception{
        var a=tpPlayer("Alice","a");var b=tpPlayer("Bob","b");var c=tpPlayer("Carol","a");var store=new TeleportStore(db);
        store.request(a.player(),"Bob",false);assertEquals("DECLINED",store.respond(b.player(),"Alice",false).state());
        store.request(c.player(),"Bob",false);assertTrue(store.cancel(c.player()));assertFalse(store.cancel(c.player()));
        var d=tpPlayer("Dave","a");var expired=store.request(d.player(),"Bob",false);
        db.transaction(sql->{update(sql,"UPDATE link_teleport_requests SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",expired.id());return null;});
        assertThrows(TeleportStore.Rejected.class,()->store.respond(b.player(),"Dave",true));
        var e=tpPlayer("Eve","a");store.request(e.player(),"Bob",false);sessions.release(e);sessions.acquire(e.player(),"a");
        assertThrows(TeleportStore.Rejected.class,()->store.respond(b.player(),"Eve",true));assertTrue(new RouteStore(db).pending("a").isEmpty());
    }
    @Test void multipleTeleportRequestsRequireExplicitRequesterAndOneAcceptanceWins()throws Exception{
        var a=tpPlayer("Alice","a");var b=tpPlayer("Bob","b");var c=tpPlayer("Carol","a");var store=new TeleportStore(db);
        store.request(a.player(),"Bob",false);store.request(c.player(),"Bob",true);
        assertThrows(TeleportStore.Rejected.class,()->store.respond(b.player(),null,true));
        try(var pool=Executors.newFixedThreadPool(2)){
            var tasks=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{try{store.respond(b.player(),"Alice",true);return true;}catch(TeleportStore.Rejected rejected){return false;}}));
            int winners=0;for(var task:tasks)if(task.get())winners++;assertEquals(1,winners);
        }
        assertEquals("Carol",store.incoming(b.player()).getFirst().requesterName());
    }
    @Test void teleportRequestAndJournalRollbackTogether()throws Exception{
        var a=tpPlayer("Alice","a");var b=tpPlayer("Bob","b");var store=new TeleportStore(db);
        db.transaction(c->{update(c,"ALTER TABLE link_outbox ADD CONSTRAINT test_tpa_reject CHECK (false)");return null;});
        try{assertThrows(Exception.class,()->store.request(a.player(),"Bob",false));assertTrue(store.incoming(b.player()).isEmpty());assertTrue(events.read("tpa:"+a.player()).isEmpty());}
        finally{db.transaction(c->{update(c,"ALTER TABLE link_outbox DROP CONSTRAINT test_tpa_reject");return null;});}
    }
    @Test void teleportDirectoryIsSharedAndOldRequestsArePruned()throws Exception{
        var a=tpPlayer("Alice","a");var b=tpPlayer("Bob","b");var store=new TeleportStore(db);
        assertEquals(List.of("Alice","Bob"),new TeleportStore(db).onlinePlayers());var request=store.request(a.player(),"Bob",false);
        db.transaction(c->{update(c,"UPDATE link_teleport_requests SET expires_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofDays(3))),request.id());return null;});
        assertEquals(1,new EventRetention(db).prune(24,100).requests());assertTrue(store.incoming(b.player()).isEmpty());
        sessions.release(b);assertEquals(List.of("Alice"),store.onlinePlayers());assertTrue(events.read("tpa:"+a.player()).isPresent());
    }
    @Test void duplicateDoesNotCreateSecondEvent() throws Exception {
        Session s=sessions.acquire(UUID.randomUUID(),"a");
        Transaction tx=change(s,UUID.randomUUID(),0,"diamond");
        assertFalse(events.commit(tx).duplicate());assertTrue(events.commit(tx).duplicate());
        assertTrue(events.history("player:"+s.player(),0,10).isEmpty());
        assertThrows(ConflictException.class,() -> events.commit(change(s,tx.operation(),0,"gold")));
    }
    @Test void allParticipantsRollbackOnConflict() throws Exception {
        Session s=sessions.acquire(UUID.randomUUID(),"a");
        events.commit(change(s,UUID.randomUUID(),0,"initial"));
        Transaction tx=new Transaction(UUID.randomUUID(),List.of(s),List.of(
                new Change("container:1",0,1,"take",new byte[]{0}),
                new Change("player:"+s.player(),0,1,"give",new byte[]{1})));
        assertThrows(ConflictException.class,() -> events.commit(tx));
        assertTrue(events.read("container:1").isEmpty());
    }
    @Test void transferFencesSourceAndIsIdempotent() throws Exception {
        UUID player=UUID.randomUUID();Session source=sessions.acquire(player,"a");
        events.commit(change(source,UUID.randomUUID(),0,"item"));
        UUID transferId=UUID.randomUUID();Destination destination=new Destination("b","spawn-2",0,64,0,0,0);
        Transfer transfer=sessions.prepare(source,transferId,destination);
        assertEquals(1,transfer.finalVersion());
        assertEquals(transfer,sessions.prepare(source,transferId,destination));
        assertThrows(ConflictException.class,() -> events.commit(change(source,UUID.randomUUID(),1,"stale")));
        assertThrows(ConflictException.class,() -> sessions.accept(player,transferId,"wrong"));
        Session target=sessions.accept(player,transferId,"b");assertEquals(source.epoch()+1,target.epoch());
        assertEquals(target,sessions.accept(player,transferId,"b"));
        assertThrows(ConflictException.class,() -> sessions.release(source));
        events.commit(change(target,UUID.randomUUID(),1,"new"));
    }
    @Test void reconnectNeverSharesAnActiveOwner() throws Exception {
        UUID player=UUID.randomUUID();Session owner=sessions.acquire(player,"a");
        assertThrows(ConflictException.class,() -> sessions.acquire(player,"a"));
        assertThrows(ConflictException.class,() -> sessions.acquire(player,"b"));
        sessions.release(owner);Session next=sessions.acquire(player,"b");
        assertTrue(next.epoch()>owner.epoch());
        assertThrows(ConflictException.class,() -> sessions.renew(owner));
    }
    @Test void expiredLeaseCannotWrite() throws Exception {
        Session s=sessions.acquire(UUID.randomUUID(),"a");
        db.transaction(c -> {update(c,"UPDATE link_sessions SET expires_at=clock_timestamp()-interval '1 second'");return null;});
        assertThrows(ConflictException.class,() -> events.commit(change(s,UUID.randomUUID(),0,"bad")));
        Session next=sessions.acquire(s.player(),"b");assertEquals(s.epoch()+1,next.epoch());
    }
    @Test void newCommitsDoNotCreateReplayHistory() throws Exception {
        Session s=sessions.acquire(UUID.randomUUID(),"a");
        events.commit(change(s,UUID.randomUUID(),0,"one"));events.commit(change(s,UUID.randomUUID(),1,"two"));
        assertTrue(events.replay("player:"+s.player(),1).isEmpty());
        assertArrayEquals("two".getBytes(),events.read("player:"+s.player()).orElseThrow().payload());
        assertEquals(2,events.read("player:"+s.player()).orElseThrow().version());
    }
    @Test void concurrentWritersOnlyOneWinsExpectedVersion() throws Exception {
        Session s=sessions.acquire(UUID.randomUUID(),"a");
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> attempts=new ArrayList<>();
            for(int i=0;i<20;i++) attempts.add(pool.submit(() -> {try {events.commit(change(s,UUID.randomUUID(),0,"x"));return true;}catch(ConflictException e){return false;}}));
            int count=0;for(var f:attempts) if(f.get())count++;
            assertEquals(1,count);
        }
    }
    @Test void concurrentAdmissionsRespectWorldAndNodeLimits() throws Exception {
        network.heartbeat("a",60);
        network.register("one",new Destination("a","spawn-1",0,64,0,0,0),50);
        network.register("two",new Destination("a","spawn-2",0,64,0,0,0),50);
        try(var pool=Executors.newFixedThreadPool(12)) {
            List<Future<Boolean>> attempts=new ArrayList<>();
            for(int i=0;i<80;i++) attempts.add(pool.submit(() -> {try {network.reserve(UUID.randomUUID(),UUID.randomUUID());return true;}catch(ConflictException e){return false;}}));
            int count=0;for(var f:attempts) if(f.get()) count++;
            assertEquals(60,count);
        }
        var channels=network.channels();assertTrue(channels.stream().allMatch(c -> c.reserved()<=50));
        assertEquals(60,channels.stream().mapToInt(Channel::reserved).sum());
    }
    @Test void reservationRetryDoesNotConsumeAnotherSlot() throws Exception {
        network.heartbeat("a",1);network.register("one",new Destination("a","spawn",0,64,0,0,0),1);
        UUID player=UUID.randomUUID(),op=UUID.randomUUID();
        assertEquals(network.reserve(player,op),network.reserve(player,op));
        Session owner=sessions.acquire(player,"a");network.admit(op,owner);
        assertThrows(ConflictException.class,() -> network.reserve(UUID.randomUUID(),UUID.randomUUID()));
        network.depart(owner);sessions.release(owner);
        assertNotNull(network.reserve(UUID.randomUUID(),UUID.randomUUID()));
    }
    @Test void templatesCannotBeOverwrittenAndVerifyDigest() throws Exception {
        TemplateStore store=new TemplateStore(db);byte[] original={1,2,3};
        assertTrue(store.find("lobby",1).isEmpty());
        assertThrows(ConflictException.class,() -> store.read("lobby",1));
        store.publish("lobby",1,original);store.publish("lobby",1,original);
        assertThrows(ConflictException.class,() -> store.publish("lobby",1,new byte[]{4}));
        assertArrayEquals(original,store.read("lobby",1));
        db.transaction(c -> {update(c,"UPDATE link_templates SET payload=?",new byte[]{9});return null;});
        assertThrows(ConflictException.class,() -> store.read("lobby",1));
        assertThrows(ConflictException.class,() -> store.find("lobby",1));
    }
    @Test void failedObjectUploadNeverPublishesTemplate()throws Exception{
        var storage=new WorldBlobsTest.Memory();storage.unavailable=true;db.worldStorage(storage);
        var templates=new TemplateStore(db);
        assertThrows(java.io.IOException.class,()->templates.publish("failed",1,new byte[]{1}));
        assertTrue(templates.find("failed",1).isEmpty());
        storage.unavailable=false;templates.publish("ok",1,new byte[]{1,2});
        assertArrayEquals(new byte[]{1,2},templates.read("ok",1));
    }
    @Test void nativeRecoveryPrefixIsLiteralAndOrdered()throws Exception{
        assertTrue(events.prefix("native:test_a/").isEmpty());
        events.commit(new Transaction(UUID.randomUUID(),List.of(),List.of(
            new Change("native:test_a/world/z",0,1,"test",new byte[]{1}),
            new Change("native:test_a/world/a",0,1,"test",new byte[]{2}),
            new Change("native:testXa/world/b",0,1,"test",new byte[]{3}))));
        assertEquals(List.of("native:test_a/world/a","native:test_a/world/z"),events.prefix("native:test_a/").stream().map(State::id).toList());
    }
    @Test void leaseInstantIsIndependentOfJvmTimeZone()throws Exception{
        var before=TimeZone.getDefault();
        try{
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
            var owner=sessions.acquire(UUID.randomUUID(),"timezone-test");
            long remaining=java.time.Duration.between(java.time.Instant.now(),owner.expiresAt()).toSeconds();
            assertTrue(remaining>=20&&remaining<=31,"Lease must be about 30 seconds, not shifted by hours");
        }finally{TimeZone.setDefault(before);}
    }
    @Test void transferKeepsSourceAdmissionUntilTargetOwnsPlayer() throws Exception {
        network.heartbeat("a",1);network.heartbeat("b",1);
        network.register("a-spawn",new Destination("a","spawn",0,64,0,0,0),1);
        network.register("b-spawn",new Destination("b","spawn",0,64,0,0,0),1);
        UUID player=UUID.randomUUID();Session source=sessions.acquire(player,"a");
        var first=network.reserve(player,UUID.randomUUID());network.admit(first.id(),source);
        var second=network.reserve(player,UUID.randomUUID(),new Destination("b","spawn",0,64,0,0,0));assertEquals("b",second.destination().node());
        assertEquals(1,network.channels().stream().filter(c -> c.node().equals("a")).findFirst().orElseThrow().occupied());
        UUID transfer=UUID.randomUUID();sessions.prepare(source,transfer,second.destination());
        Session target=sessions.accept(player,transfer,"b");network.admit(second.id(),target);
        assertEquals(0,network.channels().stream().filter(c -> c.node().equals("a")).findFirst().orElseThrow().occupied());
        assertEquals(1,network.channels().stream().filter(c -> c.node().equals("b")).findFirst().orElseThrow().occupied());
    }
    @Test void payloadsAreDefensivelyCopied() {
        byte[] bytes={1};Change change=new Change("test:a",0,1,"set",bytes);
        bytes[0]=9;change.after()[0]=8;assertEquals(1,change.after()[0]);
    }
    @Test void redisRedeliveryDoesNotDuplicateCommittedState() throws Exception {
        String uri=System.getenv("LINK_TEST_REDIS_URI");
        Assumptions.assumeTrue(uri!=null,"Set LINK_TEST_REDIS_URI for Redis integration test");
        Session owner=sessions.acquire(UUID.randomUUID(),"a");
        UUID op=UUID.randomUUID();events.commit(change(owner,op,0,"durable"));
        try(RedisOutbox relay=new RedisOutbox(db,uri)) {
            assertEquals(1,relay.flush(10));assertEquals(0,relay.flush(10));
            // Simulate a crash after Redis accepted the message but before SQL acknowledged it.
            db.transaction(c -> {update(c,"UPDATE link_outbox SET delivered=false WHERE id=?",op);return null;});
            assertEquals(1,relay.flush(10));
        }
        assertTrue(events.history("player:"+owner.player(),0,10).isEmpty());
        assertArrayEquals("durable".getBytes(),events.read("player:"+owner.player()).orElseThrow().payload());
    }
    @Test void sqlFailureRollsBackEventAndSnapshotTogether() throws Exception {
        Session owner=sessions.acquire(UUID.randomUUID(),"a");
        db.transaction(c -> {update(c,"ALTER TABLE link_outbox ADD CONSTRAINT test_reject CHECK (false)");return null;});
        try {
            assertThrows(java.sql.SQLException.class,() -> events.commit(change(owner,UUID.randomUUID(),0,"must-not-commit")));
            assertTrue(events.read("player:"+owner.player()).isEmpty());
            assertTrue(events.history("player:"+owner.player(),0,10).isEmpty());
        }finally{db.transaction(c -> {update(c,"ALTER TABLE link_outbox DROP CONSTRAINT test_reject");return null;});}
    }
    @Test void expiredReservationCannotBeReused() throws Exception {
        network.heartbeat("a",1);network.register("one",new Destination("a","spawn",0,64,0,0,0),1);
        UUID player=UUID.randomUUID(),request=UUID.randomUUID();network.reserve(player,request);
        db.transaction(c -> {update(c,"UPDATE link_reservations SET expires_at=clock_timestamp()-interval '1 second'");return null;});
        assertThrows(ConflictException.class,() -> network.reserve(player,request));
        assertNotNull(network.reserve(player,UUID.randomUUID()));
    }
    @Test void arrivalClaimIsAtomicSingleUseAndBoundToBackend()throws Exception{
        network.heartbeat("a",2);network.register("one",new Destination("a","spawn",2,65,3,0,0),2);
        UUID player=UUID.randomUUID(),ticket=UUID.randomUUID();var reservation=network.reserve(player,ticket);
        ArrivalStore arrivals=new ArrivalStore(db);arrivals.issue(ticket,reservation,null);
        assertThrows(ConflictException.class,()->arrivals.claim(player,"b"));
        var claimed=arrivals.claim(player,"a");assertEquals(reservation.destination(),claimed.destination());
        assertThrows(ConflictException.class,()->arrivals.claim(player,"a"));
        assertEquals(1,network.channels().getFirst().occupied());
    }
    @Test void invalidAdmissionRollsBackOwnershipAcquisition()throws Exception{
        network.heartbeat("a",1);network.register("one",new Destination("a","spawn",0,65,0,0,0),1);
        UUID player=UUID.randomUUID(),ticket=UUID.randomUUID();var reservation=network.reserve(player,ticket);
        ArrivalStore arrivals=new ArrivalStore(db);arrivals.issue(ticket,reservation,null);network.ready("one",false);
        assertThrows(ConflictException.class,()->arrivals.claim(player,"a"));
        // The failed claim must not leave an ACTIVE owner or consume the ticket.
        network.ready("one",true);assertNotNull(arrivals.claim(player,"a"));
    }
    @Test void warpPositionIsImmutableAfterReservation()throws Exception{
        network.heartbeat("a",2);network.register("one",new Destination("a","spawn",0,65,0,0,0),2);
        UUID player=UUID.randomUUID(),ticket=UUID.randomUUID();Destination warp=new Destination("a","spawn",8,70,9,90,0);
        var reservation=network.reserve(player,ticket,warp);
        network.register("one",new Destination("a","spawn",100,65,100,0,0),2);
        ArrivalStore arrivals=new ArrivalStore(db);arrivals.issue(ticket,reservation,null);
        assertEquals(warp,arrivals.claim(player,"a").destination());
    }
    @Test void expiredTransferCanReconnectButOldDestinationCannotClaim()throws Exception{
        UUID player=UUID.randomUUID(),transfer=UUID.randomUUID();
        Session source=sessions.acquire(player,"a");
        sessions.prepare(source,transfer,new Destination("b","spawn",0,65,0,0,0));
        assertThrows(ConflictException.class,()->sessions.acquire(player,"c"));
        db.transaction(c->{update(c,"UPDATE link_sessions SET expires_at=clock_timestamp()-interval '1 second' WHERE player=?",player);return null;});
        assertThrows(ConflictException.class,()->sessions.accept(player,transfer,"b"));
        Session recovered=sessions.acquire(player,"c");assertEquals(source.epoch()+1,recovered.epoch());
        assertThrows(ConflictException.class,()->sessions.accept(player,transfer,"b"));
        assertThrows(ConflictException.class,()->events.commit(change(source,UUID.randomUUID(),0,"stale")));
        assertNotNull(sessions.prepare(recovered,UUID.randomUUID(),new Destination("d","spawn",0,65,0,0,0)));
    }
    @Test void channelIdentityAndReservationDestinationCannotBeReused()throws Exception{
        network.heartbeat("a",2);network.heartbeat("b",2);
        Destination spawn=new Destination("a","spawn",0,65,0,0,0);network.register("one",spawn,2);
        assertThrows(ConflictException.class,()->network.register("one",new Destination("b","spawn",0,65,0,0,0),2));
        UUID player=UUID.randomUUID(),request=UUID.randomUUID();network.reserve(player,request,spawn);
        assertThrows(ConflictException.class,()->network.reserve(player,request,new Destination("a","spawn",99,65,0,0,0)));
    }
    @Test void spawnPrefersCurrentBackendAndFallsBackWhenFull()throws Exception{
        network.heartbeat("a",4);network.heartbeat("b",4);
        network.register("remote",new Destination("a","spawn",0,65,0,0,0),4);
        var local=new Destination("b","spawn",8,65,8,90,0);
        network.register("local",local,1);
        network.reserve(UUID.randomUUID(),UUID.randomUUID(),new Destination("a","spawn",0,65,0,0,0));
        UUID player=UUID.randomUUID();sessions.acquire(player,"b");
        var first=network.reserve(player,UUID.randomUUID());assertEquals(local,first.destination());
        network.cancel(first.id(),player);
        network.reserve(UUID.randomUUID(),UUID.randomUUID(),local);
        assertEquals("a",network.reserve(player,UUID.randomUUID()).destination().node());
    }
    @Test void submittedTravelWakesBackendAfterDurableRequest()throws Exception{
        network.heartbeat("a",2);network.register("one",new Destination("a","spawn",0,65,0,0,0),2);
        UUID player=UUID.randomUUID(),operation=UUID.randomUUID();sessions.acquire(player,"a");
        var observed=new java.util.concurrent.atomic.AtomicReference<RouteStore.Request>();
        try(var service=new LinkService(db,request->{
            try{assertEquals("REQUESTED",new RouteStore(db).status(request.id()));}
            catch(Exception e){throw new AssertionError(e);}
            observed.set(request);
        })){
            var reservation=service.network().requestTravel(player,operation,null).toCompletableFuture().get(5,TimeUnit.SECONDS);
            assertEquals(new RouteStore.Request(operation,reservation),observed.get());
            var routes=new RouteStore(db);routes.finish(operation,true);
            routes.ready(operation);assertEquals("COMPLETE",routes.status(operation));
            assertTrue(routes.readyFor(List.of(player)).isEmpty());assertFalse(routes.dispatch(operation));
        }
    }
    @Test void failedWakeupRetainsDurableRouteForPolling()throws Exception{
        network.heartbeat("a",2);network.register("one",new Destination("a","spawn",0,65,0,0,0),2);
        UUID player=UUID.randomUUID(),operation=UUID.randomUUID();sessions.acquire(player,"a");
        try(var service=new LinkService(db,request->{throw new IllegalStateException("scheduler unavailable");})){
            service.network().requestTravel(player,operation,null).toCompletableFuture().get(5,TimeUnit.SECONDS);
            assertEquals(operation,new RouteStore(db).pending("a").getFirst().id());
        }
    }
    @Test void routeDispatchHasOneWinnerAndCompletionIsTerminal()throws Exception{
        network.heartbeat("a",2);network.register("one",new Destination("a","spawn",0,65,0,0,0),2);
        UUID player=UUID.randomUUID(),operation=UUID.randomUUID();sessions.acquire(player,"a");
        var reservation=network.reserve(player,operation);RouteStore routes=new RouteStore(db);
        routes.request(operation,reservation);routes.request(operation,reservation);
        assertEquals(1,routes.pending("a").size());assertTrue(routes.readyFor(List.of(player)).isEmpty());
        routes.ready(operation);assertEquals(1,routes.readyFor(List.of(player)).size());
        assertTrue(routes.readyFor(List.of(UUID.randomUUID())).isEmpty());
        try(var executor=Executors.newFixedThreadPool(4)){
            var attempts=new ArrayList<Future<Boolean>>();for(int i=0;i<8;i++)attempts.add(executor.submit(()->routes.dispatch(operation)));
            int winners=0;for(var attempt:attempts)if(attempt.get())winners++;assertEquals(1,winners);
        }
        routes.finish(operation,true);routes.finish(operation,false);assertEquals("COMPLETE",routes.status(operation));
    }
    @Test void pooledConnectionRollsBackBeforeReuse()throws Exception{
        try(Database pooled=Database.pooled(System.getenv("LINK_TEST_JDBC_URL"),System.getenv("LINK_TEST_DB_USER"),System.getenv("LINK_TEST_DB_PASSWORD"))){
            assertThrows(java.sql.SQLException.class,()->pooled.transaction(c->{
                update(c,"INSERT INTO link_state VALUES ('pool-test',1,1,?)",new byte[]{1});
                throw new java.sql.SQLException("injected failure");
            }));
            assertTrue(new EventStore(pooled).read("pool-test").isEmpty());
            int value=pooled.transaction(c->{try(var s=sql(c,"SELECT 42");var r=s.executeQuery()){r.next();return r.getInt(1);}});
            assertEquals(42,value);
        }
    }
    @Test void fullChannelAllowsItsOwnPlayerToWarpWithoutOpeningAnExtraSlot()throws Exception{
        Destination spawn=new Destination("a","spawn",0,65,0,0,0);
        network.heartbeat("a",1);network.register("one",spawn,1);
        UUID player=UUID.randomUUID();Session owner=sessions.acquire(player,"a");
        network.admit(network.reserve(player,UUID.randomUUID()).id(),owner);
        network.reserve(player,UUID.randomUUID(),new Destination("a","spawn",5,65,5,0,0));
        var channel=network.channels().getFirst();assertEquals(1,channel.occupied());assertEquals(0,channel.reserved());
        assertThrows(ConflictException.class,()->network.reserve(UUID.randomUUID(),UUID.randomUUID()));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={0,1,2,3,4,5})
    void recoveryAtEveryTransferBoundaryFencesOldOwners(int boundary)throws Exception{
        network.heartbeat("a",50);network.heartbeat("b",50);
        Destination destination=new Destination("b","spawn",8,66,8,90,0);network.register("b:spawn",destination,50);
        UUID player=UUID.randomUUID(),operation=UUID.randomUUID();Session source=sessions.acquire(player,"a");
        events.commit(change(source,UUID.randomUUID(),0,"seven-diamonds"));
        var reservation=network.reserve(player,operation,destination);var routes=new RouteStore(db);var arrivals=new ArrivalStore(db);routes.request(operation,reservation);
        Session owner=source;
        if(boundary>=1)sessions.prepare(source,operation,destination);
        if(boundary>=2)arrivals.issue(operation,reservation,operation);
        if(boundary>=3)routes.ready(operation);
        if(boundary>=4)assertTrue(routes.dispatch(operation));
        if(boundary>=5)owner=arrivals.claim(player,"b").session();
        Session crashed=owner;
        db.transaction(c->{update(c,"UPDATE link_sessions SET expires_at=clock_timestamp()-interval '1 second' WHERE player=?",player);return null;});
        Session recovered=sessions.acquire(player,"c");assertTrue(recovered.epoch()>crashed.epoch());
        assertArrayEquals("seven-diamonds".getBytes(java.nio.charset.StandardCharsets.UTF_8),events.read("player:"+player).orElseThrow().payload());
        assertThrows(ConflictException.class,()->events.commit(change(source,UUID.randomUUID(),1,"stale-source")));
        assertThrows(ConflictException.class,()->events.commit(change(crashed,UUID.randomUUID(),1,"stale-destination")));
        if(boundary>=1)assertThrows(ConflictException.class,()->sessions.accept(player,operation,"b"));
        events.commit(change(recovered,UUID.randomUUID(),1,"seven-diamonds-recovered"));
        assertTrue(events.history("player:"+player,0,10).isEmpty());
    }
}
