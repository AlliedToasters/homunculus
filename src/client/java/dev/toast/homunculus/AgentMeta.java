package dev.toast.homunculus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Control-stack meta-observables for the obs-ablation recorder
 * (neural_interface.md §8f). These are NOT world observations — they are the
 * agent's internal control state, pushed from Python (craft/agent.py) via
 * {@code POST /obs/meta} and stamped onto every per-tick
 * {@link PlayerObsSnapshot} so each recorded packet line carries the active
 * goal / tool / wait state.
 *
 * <p>Why pushed, not read: {@code g_t}, the current tool, and the
 * waiting-on-LLM flag live in the Python agent loop; homunculus has no view of
 * them. The agent calls {@code /obs/meta} at its turn boundaries (before the
 * LLM call to set {@code waiting_on_llm=true}; after, to set the chosen tool +
 * goal and {@code waiting_on_llm=false}).
 *
 * <p>{@code ticks_since_g_t_issued} is derived here, not pushed: when the
 * incoming {@code g_t} string differs from the current one we restamp the
 * issue tick; otherwise we carry it forward. At snapshot time the recorder
 * reads {@code current_tick - issued_tick}. This makes the carry-forward
 * explicit-in-serialization (§8a) without the agent having to count ticks.
 *
 * <p>Concurrency: {@link #update} runs on an HTTP worker thread, {@link #read}
 * on the client tick thread. Both are {@code synchronized} so the tick thread
 * always sees a consistent field set (no torn read across g_t / tool / wait).
 */
public final class AgentMeta {

    public static final AgentMeta INSTANCE = new AgentMeta();

    private String gt = null;
    private long gtIssuedTick = -1L;
    private String currentTool = null;
    private Object currentToolArgs = null; // parsed JSON value (usually a Map) or null
    private boolean waitingOnLlm = false;

    private AgentMeta() {}

    /**
     * Merge an incoming {@code /obs/meta} body. Only keys present in the body
     * are updated, so the agent can push partial state (e.g. just
     * {@code waiting_on_llm}) without clobbering the rest. A {@code g_t} whose
     * value actually changed restamps {@link #gtIssuedTick} to {@code now};
     * an unchanged {@code g_t} carries the existing stamp forward.
     */
    public synchronized void update(Map<String, Object> body, long currentTick) {
        if (body.containsKey("g_t")) {
            Object v = body.get("g_t");
            String next = v == null ? null : v.toString();
            if (!Objects.equals(next, gt)) {
                gt = next;
                gtIssuedTick = next == null ? -1L : currentTick;
            }
        }
        if (body.containsKey("current_tool")) {
            Object v = body.get("current_tool");
            currentTool = v == null ? null : v.toString();
        }
        if (body.containsKey("current_tool_args")) {
            currentToolArgs = body.get("current_tool_args");
        }
        if (body.containsKey("waiting_on_llm")) {
            waitingOnLlm = body.get("waiting_on_llm") instanceof Boolean b && b;
        }
    }

    /**
     * Snapshot the meta state for a recorded line at {@code currentTick}.
     * {@code ticks_since_g_t_issued} is null when no goal is set; otherwise
     * {@code max(0, currentTick - gtIssuedTick)}.
     */
    public synchronized Map<String, Object> read(long currentTick) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("g_t", gt);
        m.put("ticks_since_g_t_issued",
                (gt == null || gtIssuedTick < 0) ? null
                        : (int) Math.max(0L, currentTick - gtIssuedTick));
        m.put("current_tool", currentTool);
        m.put("current_tool_args", currentToolArgs);
        m.put("waiting_on_llm", waitingOnLlm);
        return m;
    }

    /** For the /obs/meta status response. */
    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> m = read(PlayerObsSnapshot.currentTick());
        m.put("success", true);
        m.put("g_t_issued_tick", gtIssuedTick < 0 ? null : gtIssuedTick);
        return m;
    }
}
