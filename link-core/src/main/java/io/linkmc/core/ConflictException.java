package io.linkmc.core;

/** A rejected operation, not permission to retry with a new operation ID. */
public final class ConflictException extends IllegalStateException {
    public enum Kind { GENERAL, WORLD_EXISTS, WORLD_DELETED }
    private final Kind kind;
    private final String world;
    public ConflictException(String reason) { super(reason);kind=Kind.GENERAL;world=null; }
    private ConflictException(String world,boolean deleted){
        super(deleted?"World identity was deleted":"World identity already exists");
        io.linkmc.api.LinkApi.requireName(world);
        this.world=world;kind=deleted?Kind.WORLD_DELETED:Kind.WORLD_EXISTS;
    }
    public static ConflictException worldExists(String world,boolean deleted){return new ConflictException(world,deleted);}
    public Kind kind(){return kind;}
    public String world(){return world;}
}
