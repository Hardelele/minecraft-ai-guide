package hardelele.aiguide.client;

import com.google.gson.Gson;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/** An edited definition cannot rewrite the work an agent already accepted. */
public final class RoutineSnapshotTest {
    public static void main(String[] args) throws Exception {
        Routines.Routine saved = new Routines.Routine();
        saved.name = "Железо";
        saved.description = "Ищи железо";
        saved.target = "ore iron_ore";
        saved.radius = 24;
        saved.deposit = true;
        saved.vec = new double[] { 0.25, 0.75 };
        Routines.Routine running = Routines.copy(saved);

        saved.name = "Камень";
        saved.target = "block stone";
        saved.radius = 8;
        saved.deposit = false;
        saved.vec[0] = 1;
        if (!"Железо".equals(running.name) || !"ore iron_ore".equals(running.target)
                || running.radius != 24 || !running.deposit || running.vec[0] != 0.25)
            throw new AssertionError("running definition changed with saved draft");

        Routines.Routine reloaded = new Gson().fromJson(new Gson().toJson(running), Routines.Routine.class);
        if (!"Железо".equals(reloaded.name) || !"ore iron_ore".equals(reloaded.target)
                || reloaded.radius != 24 || !reloaded.deposit)
            throw new AssertionError("routines.json model lost a field");

        File root = Files.createTempDirectory(Paths.get("build"), "routine-test-").toFile();
        File worldA = new File(root, "worldA"), worldB = new File(root, "worldB");
        if (!worldA.mkdir() || !worldB.mkdir()) throw new AssertionError("test worlds not created");
        File a = new File(worldA, "routines.json"), b = new File(worldB, "routines.json");
        Routines.write(a, Arrays.asList(running));
        Routines.Routine other = Routines.copy(running); other.name = "Дерево"; other.target = "tree";
        Routines.write(b, Arrays.asList(other));
        List<Routines.Routine> changed = Routines.read(a);
        changed.get(0).name = "Новая шахта";
        Routines.write(a, changed);
        if (!"Новая шахта".equals(Routines.read(a).get(0).name) || !"Дерево".equals(Routines.read(b).get(0).name))
            throw new AssertionError("world files contaminated each other");
        if (!"Железо".equals(Routines.read(new File(worldA, "routines.json.bak")).get(0).name))
            throw new AssertionError("original definition was not backed up");

        File broken = new File(root, "corrupt.json");
        Files.write(broken.toPath(), "broken".getBytes(StandardCharsets.UTF_8));
        try { Routines.read(broken); throw new AssertionError("corrupt file read as empty list"); }
        catch (RuntimeException expected) { }
        if (!"broken".equals(new String(Files.readAllBytes(broken.toPath()), StandardCharsets.UTF_8)))
            throw new AssertionError("corrupt source was modified");
        File duplicate = new File(root, "duplicate.json");
        Files.write(duplicate.toPath(), ("[{\"name\":\"Mine\",\"target\":\"tree\",\"radius\":24},"
                + "{\"name\":\"mine\",\"target\":\"ore iron_ore\",\"radius\":24}]").getBytes(StandardCharsets.UTF_8));
        try { Routines.read(duplicate); throw new AssertionError("duplicate names accepted"); }
        catch (IllegalStateException expected) { }
        File hidden = new File(root, "invalid-target.json");
        Files.write(hidden.toPath(), "[{\"name\":\"Bad\",\"target\":\"ore \"}]".getBytes(StandardCharsets.UTF_8));
        try { Routines.read(hidden); throw new AssertionError("invalid target accepted"); }
        catch (IllegalStateException expected) { }
        File legacy = new File(root, "legacy.json");
        Files.write(legacy.toPath(), "[{\"name\":\"Legacy\",\"target\":\"tree\"}]".getBytes(StandardCharsets.UTF_8));
        if (Routines.read(legacy).get(0).radius != 24)
            throw new AssertionError("existing definitions without explicit radius stopped loading");
        System.out.println("RoutineSnapshotTest PASS");
    }
}
