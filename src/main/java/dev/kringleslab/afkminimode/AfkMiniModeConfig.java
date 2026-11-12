package dev.kringleslab.afkminimode;

import java.awt.Color;
import lombok.AllArgsConstructor;
import lombok.Getter;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Units;

@ConfigGroup(AfkMiniModeConfig.GROUP)
public interface AfkMiniModeConfig extends Config
{
	String GROUP = "afk-mini-mode";

	@Getter
	@AllArgsConstructor
	enum Corner
	{
		TOP_LEFT(false, false),
		TOP_RIGHT(true, false),
		BOTTOM_LEFT(false, true),
		BOTTOM_RIGHT(true, true);

		private final boolean right;
		private final boolean bottom;
	}

	@Getter
	@AllArgsConstructor
	enum Monitor
	{
		CURRENT("Current", 0),
		PRIMARY("Primary", -1),
		MONITOR_1("Monitor 1", 1),
		MONITOR_2("Monitor 2", 2),
		MONITOR_3("Monitor 3", 3),
		MONITOR_4("Monitor 4", 4);

		private final String label;
		// 0 = the one RuneLite is on, -1 = primary, n = nth screen
		private final int index;

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigSection(name = "Window", description = "Where mini mode puts the client", position = 0)
	String window = "window";

	@ConfigSection(
		name = "Hide in mini mode",
		description = "UI hidden while mini mode is on (resizable modes only)",
		position = 1
	)
	String hide = "hide";

	@ConfigItem(
		keyName = "toggleKey",
		name = "Toggle hotkey",
		description = "Switch mini mode on/off. To hide the sidebar, use RuneLite's own 'Sidebar toggle' hotkey (RuneLite > Window Settings) on a different key.",
		position = -1
	)
	default Keybind toggleKey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "rememberPosition",
		name = "Remember dragged position",
		description = "Drag the client by its title bar while in mini mode and it reopens there next time. Off = always snap to the corner.",
		section = window,
		position = -2
	)
	default boolean rememberPosition()
	{
		return true;
	}

	@ConfigItem(keyName = "savedX", name = "", description = "", hidden = true)
	default int savedX()
	{
		return Integer.MIN_VALUE;
	}

	@ConfigItem(keyName = "savedY", name = "", description = "", hidden = true)
	default int savedY()
	{
		return Integer.MIN_VALUE;
	}

	@ConfigItem(
		keyName = "corner",
		name = "Corner",
		description = "Screen corner to move the client to",
		section = window,
		position = 0
	)
	default Corner corner()
	{
		return Corner.BOTTOM_RIGHT;
	}

	@ConfigItem(
		keyName = "monitor",
		name = "Monitor",
		description = "Current = whichever monitor RuneLite is on. Primary = your main Windows display. Monitor 1-4 may be numbered differently from Windows' settings; try each. A monitor you don't have counts as Current.",
		section = window,
		position = -1
	)
	default Monitor monitor()
	{
		return Monitor.CURRENT;
	}

	@Units(Units.PIXELS)
	@ConfigItem(keyName = "paddingX", name = "Horizontal padding", description = "Gap from the screen edge", section = window, position = 1)
	default int paddingX()
	{
		return 0;
	}

	@Units(Units.PIXELS)
	@ConfigItem(keyName = "paddingY", name = "Vertical padding", description = "Gap from the screen edge", section = window, position = 2)
	default int paddingY()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "alwaysOnTop",
		name = "Always on top",
		description = "Keep the client above other windows while in mini mode",
		section = window,
		position = 3
	)
	default boolean alwaysOnTop()
	{
		return true;
	}

	@Range(min = 20, max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "opacity",
		name = "Window opacity",
		description = "See-through window while in mini mode (uses RuneLite's own Window opacity setting, restored when you leave).",
		section = window,
		position = 4
	)
	default int opacity()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "opacityToggleKey",
		name = "Opacity hotkey",
		description = "While in mini mode, flip between the opacity above and fully solid",
		section = window,
		position = 5
	)
	default Keybind opacityToggleKey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(keyName = "hideMinimap", name = "Minimap", description = "Hide the minimap", section = hide, position = 0)
	default boolean hideMinimap()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hideOrbs",
		name = "Orbs, world map, wiki & store",
		description = "Hide the HP/prayer/run/spec orbs, world map, wiki, activity adviser and store buttons",
		section = hide,
		position = 1
	)
	default boolean hideOrbs()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hideChat",
		name = "Chat",
		description = "Hide the chatbox and the promo banner above it, including dialogues, make-X menus and amount prompts. Leave mini mode to use those. The status panel takes its place.",
		section = hide,
		position = 2
	)
	default boolean hideChat()
	{
		return true;
	}

	@ConfigItem(keyName = "hideXpDrops", name = "XP drops", description = "Hide XP drops. Off = the game's XP counter moves to the top-left in mini mode and replaces the panel's skill row", section = hide, position = 3)
	default boolean hideXpDrops()
	{
		return true;
	}

	@ConfigItem(
		keyName = "pausePlugins",
		name = "Pause plugins",
		description = "Comma-separated plugin names to pause while in mini mode, e.g. Ground Items, NPC Indicators. Resumed when you leave mini mode.",
		section = hide,
		position = 4
	)
	default String pausePlugins()
	{
		return "Ground Items";
	}

	@ConfigItem(
		keyName = "hidePanels",
		name = "Other plugins' info panels",
		description = "Hide info boxes and panels from other plugins (e.g. Fishing catch stats, ping). The plugins keep running, so world highlights like fishing spots stay.",
		section = hide,
		position = 5
	)
	default boolean hidePanels()
	{
		return true;
	}

	@ConfigSection(
		name = "Status panel",
		description = "Skill, inventory and barrel/basket rows, pinned to the bottom-left in mini mode where the chat was.",
		position = 2
	)
	String bar = "bar";

	@ConfigItem(keyName = "showBar", name = "Show status panel", description = "Show the status panel while in mini mode", section = bar, position = 0)
	default boolean showBar()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showSkill",
		name = "Skill progress",
		description = "Progress to the next level in the skill you last gained XP in. Only shown when XP drops are hidden; otherwise the game's own XP counter moves to the top-left instead.",
		section = bar,
		position = 1
	)
	default boolean showSkill()
	{
		return true;
	}

	@ConfigItem(keyName = "showInventory", name = "Inventory space", description = "Free inventory slots", section = bar, position = 2)
	default boolean showInventory()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showContainers",
		name = "Fish barrel / log basket",
		description = "A row for an open fish barrel or log basket. Use 'Check' on it once so the count is exact.",
		section = bar,
		position = 3
	)
	default boolean showContainers()
	{
		return true;
	}

	@ConfigItem(keyName = "barText", name = "Show text", description = "Write the numbers on the bars", section = bar, position = 4)
	default boolean barText()
	{
		return true;
	}

	@Range(min = 80, max = 400)
	@Units(Units.PIXELS)
	@ConfigItem(keyName = "barWidth", name = "Width", description = "Width of the status panel", section = bar, position = 5)
	default int barWidth()
	{
		return 200;
	}

	@Range(min = 12, max = 40)
	@Units(Units.PIXELS)
	@ConfigItem(keyName = "barHeight", name = "Row height", description = "Height of each row (icons scale with it)", section = bar, position = 6)
	default int barHeight()
	{
		return 20;
	}

	@Alpha
	@ConfigItem(keyName = "skillColor", name = "Skill colour", description = "Fill colour of the skill progress bar", section = bar, position = 7)
	default Color skillColor()
	{
		// close to the game's own XP counter bar
		return new Color(180, 40, 20);
	}

	@Alpha
	@ConfigItem(keyName = "inventoryColor", name = "Inventory colour", description = "Fill colour of the inventory bar", section = bar, position = 8)
	default Color inventoryColor()
	{
		return new Color(200, 140, 40);
	}

	@Alpha
	@ConfigItem(keyName = "fullColor", name = "Full colour", description = "Inventory and barrel/basket bar colour once full", section = bar, position = 9)
	default Color fullColor()
	{
		return new Color(200, 40, 40);
	}
}
