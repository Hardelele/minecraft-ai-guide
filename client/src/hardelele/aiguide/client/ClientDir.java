package hardelele.aiguide.client;

/** the game client's folder, in a class of its own (a dedicated server never loads the client's classes) */
class ClientDir {
    static java.io.File get() {
        return net.minecraft.client.Minecraft.getMinecraft().mcDataDir;
    }
}
