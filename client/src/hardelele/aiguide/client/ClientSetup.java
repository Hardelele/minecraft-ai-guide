package hardelele.aiguide.client;

/** what only the game client sets up: the agents' looks, the crew's lines to the director, the pilot */
class ClientSetup {
    static void run() {
        cpw.mods.fml.client.registry.RenderingRegistry.registerEntityRenderingHandler(Agent.class, new RenderAgent());
        Events.sink = new Events.Sink() { public void log(String line) { Director.log(line); Episodes.event(System.currentTimeMillis(), line); } };   // the crew's lines: to the director, and kept for the ratings and the share
        Events.ghost = new Events.Ghost() { public void show(com.google.gson.JsonObject m) { if (Pilot.instance != null) Pilot.instance.inbox.add(m); } };
        Pilot.start();
        Commander.start();
        CrewKeys.start();
        Fog.start();
        Slice.start();
        CaveView.start();
        Feedback.start();
        WavesHud.start();
    }
}
