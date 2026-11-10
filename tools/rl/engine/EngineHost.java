import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

/**
 * N learning arenas in one JVM (docs/rl/ENGINE.md): each a Forge dedicated server of its own, loaded by a class loader
 * of its own (Minecraft, Forge, FML and the mods: none of their static state shared), its own world folder
 * (--universe), its own learning port (-Daiguide.rl, set before it starts: the arenas start one after another), all
 * ticking at once on their own server threads. The protocol of each is RlEnv's, unchanged.
 *
 *   java -cp build/engine-host EngineHost --dir build/rl/servers/engine --ports 26200,26201
 *
 * --dir holds Forge's jar and libraries/ (the template), mods/ and config/ (shared, read at start), server.properties
 * (shared: the port is --port, 0 here: no Minecraft client connects); arena-PORT/world is each arena's world.
 */
public class EngineHost {
    /** the host's own stderr (FML puts its logger in System.err's place) */
    static final java.io.PrintStream OUT = System.err;

    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (Throwable e) {
            OUT.println("the engine host failed: " + e);
            e.printStackTrace(OUT);
            try { noSecurityManager(); } catch (Exception x) { /* exits anyway below, or is killed */ }
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        String dir = ".", ports = "26200";
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (args[i].equals("--dir")) dir = args[i + 1];
            else if (args[i].equals("--ports")) ports = args[i + 1];
        }
        File d = new File(dir).getAbsoluteFile();
        File forge = null;
        for (File f : d.listFiles()) if (f.getName().startsWith("forge-") && f.getName().endsWith("-universal.jar")) forge = f;
        if (forge == null) throw new IllegalStateException("no Forge jar in " + d);
        List<URL> urls = new ArrayList<URL>();
        urls.add(forge.toURI().toURL());
        try (JarInputStream in = new JarInputStream(new FileInputStream(forge))) {
            Manifest m = in.getManifest();
            for (String p : m.getMainAttributes().getValue("Class-Path").split(" "))
                if (!p.trim().isEmpty()) urls.add(new File(d, p.trim()).toURI().toURL());
        }
        ClassLoader top = ClassLoader.getSystemClassLoader().getParent();   // the JDK's only: nothing of the host shared
        long t0 = System.currentTimeMillis();
        for (String ps : ports.split(",")) {
            final int port = Integer.parseInt(ps.trim());
            File universe = new File(d, "arena-" + port);
            universe.mkdirs();
            noSecurityManager();   // FML installs one that refuses to be replaced: the next arena's FML installs its own
            System.setProperty("aiguide.rl", String.valueOf(port));
            final URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), top);
            final String[] a = { "--tweakClass", "cpw.mods.fml.common.launcher.FMLServerTweaker", "--gameDir", d.getPath(),
                    "--universe", universe.getPath(), "--port", "0", "nogui" };
            Thread t = new Thread(() -> {
                try {
                    Class<?> launch = Class.forName("net.minecraft.launchwrapper.Launch", true, cl);
                    Method main = launch.getMethod("main", String[].class);
                    main.invoke(null, (Object) a);
                } catch (Throwable e) {
                    OUT.println("arena " + port + " failed to start: " + e);
                    e.printStackTrace(OUT);
                }
            }, "Arena-" + port + "-launch");
            t.setContextClassLoader(cl);
            t.start();
            long s0 = System.currentTimeMillis();
            while (!listening(port)) {   // its environment is up: the next arena may take the property
                if (System.currentTimeMillis() - s0 > 600000) throw new IllegalStateException("arena " + port + " did not start in 10 min");
                Thread.sleep(250);
            }
            OUT.println("arena " + port + " ready in " + (System.currentTimeMillis() - s0) + " ms");
        }
        OUT.println("all arenas ready in " + (System.currentTimeMillis() - t0) + " ms");
        Object lock = new Object();
        synchronized (lock) { lock.wait(); }   // the arenas' threads run on; a shutdown on any port stops only that arena
    }

    static boolean listening(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 200);
            s.getOutputStream().write("{\"op\":\"close\"}\n".getBytes("UTF-8"));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void noSecurityManager() throws Exception {
        if (System.getSecurityManager() == null) return;
        // System.security is filtered out of getDeclaredField (Oracle 8u401 too): the unfiltered list of the fields
        Method all = Class.class.getDeclaredMethod("getDeclaredFields0", boolean.class);
        all.setAccessible(true);
        for (Field f : (Field[]) all.invoke(System.class, false))
            if (f.getName().equals("security")) {
                f.setAccessible(true);
                f.set(null, null);
                return;
            }
        throw new IllegalStateException("System.security not found");
    }
}
