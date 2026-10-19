package mcopt.metal;

import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.swing.JOptionPane;

/** The jar's Main-Class: someone ran (double-clicked) the mod jar, so explain that it's a mod and how to install it. */
public final class InstallHelp {
	private InstallHelp() {
	}

	public static void main(String[] args) {
		String version = InstallHelp.class.getPackage().getImplementationVersion();
		String json = modJson();
		String name = modName(json);
		String text = name + (version == null ? "" : " " + version) + " is a Fabric mod for Minecraft: Java Edition 26.3, not an app to open.\n"
			+ "\n"
			+ "To install it (Apple Silicon Mac, macOS 15 or later):\n"
			+ "1. Install Fabric for Minecraft 26.3: https://fabricmc.net/use/installer/\n"
			+ "2. Put this jar in your mods folder (no Sodium: take it out if it's there):\n"
			+ "   ~/Library/Application Support/minecraft/mods (official launcher)\n"
			+ "3. Start Minecraft with the Fabric profile.\n"
			+ "\n"
			+ "The README on the mcopt GitHub page has the details.";
		System.out.println(text);
		if (!GraphicsEnvironment.isHeadless()) {
			JOptionPane.showMessageDialog(null, text, name, JOptionPane.INFORMATION_MESSAGE);
		}
	}

	/** This jar's fabric.mod.json ("" if it can't be read). */
	private static String modJson() {
		try (InputStream in = InstallHelp.class.getResourceAsStream("/fabric.mod.json")) {
			return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			return "";
		}
	}

	/** The mod's "name" as Fabric shows it (its refusal dialogs use it too), so the dialog calls the jar what Fabric does. */
	private static String modName(String json) {
		int at = json.indexOf("\"name\"");
		int colon = at < 0 ? -1 : json.indexOf(':', at), open = colon < 0 ? -1 : json.indexOf('"', colon), close = open < 0 ? -1 : json.indexOf('"', open + 1);
		return close > open + 1 ? json.substring(open + 1, close) : "mcopt";
	}
}
