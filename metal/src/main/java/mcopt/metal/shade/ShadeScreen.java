package mcopt.metal.shade;

import com.mojang.serialization.Codec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * The shader settings: every slider applies live, and closing the screen writes the values to config/mcopt.properties so the next
 * launch starts from them (a -D on the command line still wins). Opened from Mod Menu or from Video Settings. Plain-text captions,
 * since without Fabric API a mod's lang files aren't loaded.
 */
public final class ShadeScreen extends OptionsSubScreen {
	private static final String[] VIEWS = {"Lit", "Normals", "Shadow", "AO"};
	private boolean enabled = Shade.ON && Shade.enabled;

	public ShadeScreen(Screen last) {
		super(last, Minecraft.getInstance().options, Component.literal("mcopt Shaders"));
	}

	@Override
	protected void addOptions() {
		this.list.addBig(OptionInstance.createBoolean("Shaders",
			v -> Tooltip.create(Component.literal(Shade.ON ? "Switches the shader pass off and on live."
				: "mcopt Shaders weren't on at launch: turning this on takes effect after a restart (it also turns on mcopt's terrain renderer).")),
			(caption, v) -> Options.genericValueLabel(Component.literal("Shaders"), Component.literal(v ? (Shade.ON ? "ON" : "ON after restart") : "OFF")),
			this.enabled, v -> {
				this.enabled = v;
				if (Shade.ON) Shade.enabled = v;
			}));
		this.list.addSmall(
			slider("Shadow strength", "How dark sun shadows are.", 0, 1, Shade.SHADOW, Shade::pct, v -> Shade.SHADOW = (float) v),
			slider("Sun colour", "Warm sun at dawn and dusk, cool moonlight at night.", 0, 1, Shade.LIGHT, Shade::pct, v -> Shade.LIGHT = (float) v),
			slider("Ambient occlusion", "Darkening in corners and crevices.", 0, 1, Shade.AO, Shade::pct, v -> Shade.AO = (float) v),
			slider("AO radius", "How far ambient occlusion reaches, in blocks.", 0.25, 4, Shade.AO_RADIUS, Shade::blocks, v -> Shade.AO_RADIUS = (float) v),
			slider("Colour grade", "Filmic tonemap and contrast.", 0, 1, Shade.GRADE, Shade::pct, v -> Shade.GRADE = (float) v),
			slider("Contact shadows", "Reach of the screen-space shadows, in blocks.", 4, 64, Shade.SHADOW_LEN, Shade::blocks, v -> Shade.SHADOW_LEN = (float) v),
			OptionInstance.createBoolean("Shadow map",
				v -> Tooltip.create(Component.literal("Real sun shadows from the terrain. Off: screen-space contact shadows only.")),
				Shade.MAP, v -> Shade.MAP = v),
			new OptionInstance<>("Shadow refresh", v -> Tooltip.create(Component.literal("Redraw the shadow map at least every N frames (also when you move a block or the sun moves). 1 = every frame; higher is faster, block edits show a little later.")),
				(caption, v) -> Options.genericValueLabel(Component.literal("Shadow refresh"), Component.literal(v == 1 ? "Every frame" : "Every " + v + " frames")),
				new OptionInstance.IntRange(1, 16), Math.clamp(Shade.MAP_EVERY, 1, 16), v -> Shade.MAP_EVERY = v),
			slider("Shadow distance", "Blocks around you that cast sun shadows. Shorter is sharper.", 32, 256, Shade.MAP_RADIUS, Shade::blocks, v -> Shade.MAP_RADIUS = (float) v),
			new OptionInstance<>("View", v -> Tooltip.create(Component.literal("Debug views of the shader's inputs.")),
				(caption, v) -> Options.genericValueLabel(Component.literal("View"), Component.literal(VIEWS[v])),
				new OptionInstance.Enum<>(List.of(0, 1, 2, 3), Codec.INT), Shade.debugNow, v -> Shade.debugNow = v));
		this.list.addBig(Button.builder(Component.literal("Reset to defaults"), b -> {
			Shade.reset();
			this.minecraft.gui.setScreen(new ShadeScreen(this.lastScreen));
		}).build());
	}

	private static OptionInstance<Double> slider(String name, String tip, double min, double max, double value, DoubleFunction<String> label, DoubleConsumer set) {
		return new OptionInstance<>(name, v -> Tooltip.create(Component.literal(tip)),
			(caption, v) -> Options.genericValueLabel(Component.literal(name), Component.literal(label.apply(v))),
			OptionInstance.UnitDouble.INSTANCE.xmap(t -> min + t * (max - min), v -> (v - min) / (max - min)),
			Math.clamp(value, min, max), set::accept);
	}

	@Override
	public void removed() {
		super.removed();
		Map<String, String> values = new LinkedHashMap<>();
		values.put("mcopt.shade", Boolean.toString(this.enabled));
		values.put("mcopt.shade.shadow", num(Shade.SHADOW));
		values.put("mcopt.shade.light", num(Shade.LIGHT));
		values.put("mcopt.shade.ao", num(Shade.AO));
		values.put("mcopt.shade.aoRadius", num(Shade.AO_RADIUS));
		values.put("mcopt.shade.grade", num(Shade.GRADE));
		values.put("mcopt.shade.shadowLen", num(Shade.SHADOW_LEN));
		values.put("mcopt.shade.map", Boolean.toString(Shade.MAP));
		values.put("mcopt.shade.mapRadius", num(Shade.MAP_RADIUS));
		values.put("mcopt.shade.mapEvery", Integer.toString(Shade.MAP_EVERY));
		save(FabricLoader.getInstance().getConfigDir().resolve("mcopt.properties"), values);
	}

	private static String num(float v) {
		return String.format(Locale.ROOT, "%.3f", v);
	}

	/** Replaces each key's line in the file (or adds it at the end), leaving every other line as it was. */
	static void save(Path file, Map<String, String> values) {
		try {
			List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<>();
			Map<String, String> left = new LinkedHashMap<>(values);
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i).trim();
				int eq = line.indexOf('=');
				if (line.startsWith("#") || eq < 0) continue;
				String v = left.remove(line.substring(0, eq).trim());
				if (v != null) lines.set(i, line.substring(0, eq).trim() + "=" + v);
			}
			if (!left.isEmpty()) {
				lines.add("# mcopt Shaders (written by the settings screen)");
				left.forEach((k, v) -> lines.add(k + "=" + v));
			}
			Files.createDirectories(file.getParent());
			Files.write(file, lines);
		} catch (IOException e) {
			System.out.println("[mcopt] shade: couldn't save settings: " + e);
		}
	}
}
