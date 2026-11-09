package io.linkmc.core;

/** A rejected operation, not permission to retry with a new operation ID. */
public final class ConflictException extends IllegalStateException {
    public enum Kind { GENERAL, WORLD_EXISTS, WORLD_DELETED, OWNERSHIP_LOST }
    private final Kind kind;
    private final String world;
    public ConflictException(String reason) { super(reason);kind=Kind.GENERAL;world=null; }
    private ConflictException(Kind kind,String reason){super(reason);this.kind=kind;world=null;}
    /** This backend no longer owns its state (replaced instance or expired lease). Never retry; stop writing. */
    public static ConflictException ownershipLost(String reason){return new ConflictException(Kind.OWNERSHIP_LOST,reason);}
    public boolean ownershipLost(){return kind==Kind.OWNERSHIP_LOST;}
    private ConflictException(String world,boolean deleted){
        super(deleted?"World identity was deleted":"World identity already exists");
        io.linkmc.api.LinkApi.requireName(world);
        this.world=world;kind=deleted?Kind.WORLD_DELETED:Kind.WORLD_EXISTS;
    }
    public static ConflictException worldExists(String world,boolean deleted){return new ConflictException(world,deleted);}
    public Kind kind(){return kind;}
    public String world(){return world;}
}
