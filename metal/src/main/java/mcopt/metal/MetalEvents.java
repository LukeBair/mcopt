package mcopt.metal;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;

/** Opt-in CPU-side spans. JFR timestamps line these up with GC, samples and benchmark frames. */
final class MetalEvents {
	private static final boolean ENABLED = Boolean.getBoolean("mcopt.metal.jfr");

	@Name("mcopt.MetalOperation")
	@Label("Metal operation")
	@Category("mcopt")
	@Threshold("1 ms")
	@StackTrace(true)
	static final class Operation extends Event {
		@Label("Operation") String operation;
		@Label("Submit") long submit;
		@Label("Bytes") @jdk.jfr.DataAmount long bytes;
		@Label("Main command buffer GPU microseconds") double gpuMicros;
	}

	static Operation begin(String operation, long submit, long bytes) {
		if (!ENABLED) return null;
		Operation event = new Operation();
		if (!event.isEnabled()) return null;
		event.operation = operation;
		event.submit = submit;
		event.bytes = bytes;
		event.begin();
		return event;
	}

	static void end(Operation event) {
		if (event == null) return;
		event.end();
		event.commit();
	}
}
