package dev.toast.homunculus;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class HomunculusHttpServer {
	private static final AtomicInteger THREAD_ID = new AtomicInteger(0);
	private static final byte[] NOT_FOUND_BODY =
			"{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8);

	private final int port;
	private HttpServer server;
	private ExecutorService executor;

	public HomunculusHttpServer(int port) {
		this.port = port;
	}

	public void start() throws IOException {
		if (server != null) return;
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 4);
		executor = Executors.newFixedThreadPool(2, r -> {
			Thread t = new Thread(r, "homunculus-http-" + THREAD_ID.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
		server.setExecutor(executor);
		server.createContext("/inventory", new InventoryHandler());
		server.createContext("/position", new PositionHandler());
		server.createContext("/scan_column", new ScanColumnHandler());
		server.createContext("/scan_entities", new ScanEntitiesHandler());
		server.createContext("/scan_blocks", new ScanBlocksHandler());
		server.createContext("/scan_nearest", new ScanNearestHandler());
		server.createContext("/stats", new StatsHandler());
		server.createContext("/craft", new CraftHandler());
		server.createContext("/bed/place", new BedPlaceHandler());
		server.createContext("/bed/sleep", new SleepHandler());
		server.createContext("/shear/sheep", new ShearHandler());
		// /attack_entity: melee-attack one entity chosen by UUID (from /scan_entities).
		// Injection path for the neural target-selector (neural_interface.md §13.1) —
		// rotate + gameMode.attack + swing the chosen target; no KillAura auto-aim needed.
		server.createContext("/attack_entity", new AttackEntityHandler());
		server.createContext("/place", new PlaceHandler());
		server.createContext("/place_at", new PlaceAtHandler());
		server.createContext("/equip", new EquipHandler());
		// Food policy for the offhand-food curator (Equipper): ANY (daily-driver)
		// vs COOKED_ONLY (cook-capability tests — raw meat never auto-eaten).
		server.createContext("/food_policy", new FoodPolicyHandler());
		server.createContext("/smelt", new SmeltHandler());
		server.createContext("/smelt_status", new SmeltStatusHandler());
		server.createContext("/collect_smelt", new CollectSmeltHandler());
		server.createContext("/deaths", new DeathsHandler());
		// Phase 0 of the codec experiment (ml.MD §4a): outbound packet tap.
		// HEAD-only mixin into Connection.sendPacket counts every serverbound
		// packet by class + keeps a small ring buffer for spot-checking.
		// No mutation surface yet; Phase 1 will add encode/decode substitution
		// at the same seam, gated on a kill switch.
		PacketsHandler packetsHandler = new PacketsHandler();
		server.createContext("/packets/stats", packetsHandler);
		server.createContext("/packets/recent", packetsHandler);
		// Phase 1: byte round-trip kill switch + counters. POST {"enabled":bool}
		// flips the global flag; the mixin checks it per outbound packet.
		server.createContext("/packets/roundtrip", new PacketRoundtripHandler());
		// Phase 2 prep: capture (packet, obs) pairs to JSONL so the Python
		// structured codec can be tested offline against real rollouts
		// before any live substitution work (ml.MD §4a test-ladder step 1).
		PacketRecordingHandler recordingHandler = new PacketRecordingHandler();
		server.createContext("/packets/recording/arm", recordingHandler);
		server.createContext("/packets/recording/disarm", recordingHandler);
		server.createContext("/packets/recording/status", recordingHandler);
		// Phase 2 step 2: live structured-codec passthrough. When armed,
		// every allowlisted outbound packet's fields are POSTed to a Python
		// codec server (craft.codec.server), round-tripped, and drift counters
		// incremented. Does NOT substitute bytes on the wire — that's a
		// separate lift after step 2 shows zero drift.
		CodecPassthroughHandler codecPassthroughHandler = new CodecPassthroughHandler();
		server.createContext("/codec/passthrough/arm", codecPassthroughHandler);
		server.createContext("/codec/passthrough/disarm", codecPassthroughHandler);
		server.createContext("/codec/passthrough/status", codecPassthroughHandler);
		// Control-stack meta-observables (neural_interface.md §8f): the agent
		// pushes g_t / current_tool / waiting_on_llm here at turn boundaries;
		// every recorded packet line is stamped with the carry-forwarded state.
		server.createContext("/obs/meta", new ObsMetaHandler());
		// Heavy tick-indexed obs sidecar (neural_interface.md §8e): block cube +
		// entity list + baritone_state, one row per tick, joined to the packet
		// recording by tick. Independent lifecycle so light recordings stay light.
		ObsSidecarHandler obsSidecarHandler = new ObsSidecarHandler();
		server.createContext("/obs/sidecar/arm", obsSidecarHandler);
		server.createContext("/obs/sidecar/disarm", obsSidecarHandler);
		server.createContext("/obs/sidecar/status", obsSidecarHandler);
		server.createContext("/debug/door_courtesy", new DoorCourtesyDebugHandler());
		// Reflexive evasion — one handler, three paths. Python arms once per turn,
		// optionally polls /status mid-turn, disarms at end. The watcher itself
		// cancels Baritone and flees on hostile hit; handlers stay evasion-unaware.
		EvasionHandler evasionHandler = new EvasionHandler();
		server.createContext("/evasion/arm", evasionHandler);
		server.createContext("/evasion/disarm", evasionHandler);
		server.createContext("/evasion/status", evasionHandler);
		// Reflexive water aversion — same shape: arm once per turn, /status polls, /disarm at end.
		// Trigger is eye-submergence; destination is computed at fire time (no anchor in /arm body).
		WaterAversionHandler waterAversionHandler = new WaterAversionHandler();
		server.createContext("/water_aversion/arm", waterAversionHandler);
		server.createContext("/water_aversion/disarm", waterAversionHandler);
		server.createContext("/water_aversion/status", waterAversionHandler);
		// Wurst bridge: handlers self-check via Wurst.isApiLoaded() and return
		// wurst_not_loaded if the Wurst jar isn't on the runtime classpath, so
		// no separate stub-handler dance like /baritone/* needs.
		server.createContext("/wurst/hack", new WurstHackHandler());
		server.createContext("/wurst/status", new WurstStatusHandler());
		server.createContext("/wurst/setting", new WurstSettingHandler());
		// /wurst/hud toggles Wurst's on-screen HUD (logo, hack list, TabGui) via
		// the WurstIngameHudMixin render-cancel. Hidden by default for clean
		// headless recordings; POST {"visible": true} to restore.
		server.createContext("/wurst/hud", new WurstHudHandler());
		// /hud toggles vanilla in-game HUD elements (health, food, air, hotbar,
		// effects, xp, crosshair, selected-item) via GuiHudMixin render-cancels.
		// All visible by default; POST a per-element map (or {"all": false}) to hide.
		server.createContext("/hud", new HudHandler());
		// /baritone/mine: BOM construction is prewarmed off the render thread to dodge the
		// BlockOptionalMeta.drops() deadlock (see MineHandler.runMine). If this turns out to
		// still hang, drop /baritone/mine from the route table and revert to xdotool #mine.
		if (Baritone.isApiLoaded()) {
			server.createContext("/baritone/mine", new MineHandler());
			server.createContext("/baritone/goto", new GotoHandler());
			server.createContext("/baritone/follow", new FollowHandler());
			server.createContext("/baritone/stop", new StopHandler());
			server.createContext("/baritone/excavate", new ExcavateHandler());
			server.createContext("/baritone/fill", new FillHandler());
			server.createContext("/baritone/throwaway_items", new ThrowawayItemsHandler());
			server.createContext("/baritone/allow_break", new AllowBreakHandler());
			server.createContext("/baritone/render", new BaritoneRenderHandler());
		} else {
			BaritoneStubHandler stub = new BaritoneStubHandler();
			server.createContext("/baritone/mine", stub);
			server.createContext("/baritone/goto", stub);
			server.createContext("/baritone/follow", stub);
			server.createContext("/baritone/stop", stub);
			server.createContext("/baritone/excavate", stub);
			server.createContext("/baritone/fill", stub);
			server.createContext("/baritone/throwaway_items", stub);
			server.createContext("/baritone/allow_break", stub);
			server.createContext("/baritone/render", stub);
			HomunculusClient.LOGGER.warn("Baritone API not on classpath — /baritone/* will return baritone_not_loaded");
		}
		server.createContext("/", exchange -> {
			try {
				exchange.getResponseHeaders().add("content-type", "application/json");
				exchange.sendResponseHeaders(404, NOT_FOUND_BODY.length);
				exchange.getResponseBody().write(NOT_FOUND_BODY);
			} finally {
				exchange.close();
			}
		});
		server.start();
		HomunculusClient.LOGGER.info("HTTP server listening on 127.0.0.1:{}", port);
	}

	public void stop() {
		if (server != null) {
			server.stop(1);
			server = null;
		}
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
		HomunculusClient.LOGGER.info("HTTP server stopped");
	}
}
