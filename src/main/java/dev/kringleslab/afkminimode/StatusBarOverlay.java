package dev.kringleslab.afkminimode;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.BackgroundComponent;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.QuantityFormatter;

/**
 * Status panel in the (hidden) chat area. Skill row mimics the game's XP counter: icon, total XP, thin progress bar.
 * Below it, inventory and barrel/basket rows. Pinned to the canvas bottom-left.
 */
class StatusBarOverlay extends Overlay
{
	private static final int INVENTORY_SIZE = 28;
	private static final int PAD = 4;
	private static final int MARGIN = 4;
	private static final int GAP = 3;
	private static final int SKILL_BAR_HEIGHT = 5;
	private static final Color BAR_BACKGROUND = new Color(0, 0, 0, 160);
	private static final Color CHECK_TEXT = new Color(255, 255, 0);

	private final Client client;
	private final AfkMiniModePlugin plugin;
	private final AfkMiniModeConfig config;
	private final SkillIconManager skillIcons;
	private final SpriteManager sprites;
	private final ItemManager items;
	private final TooltipManager tooltipManager;
	private final BackgroundComponent background = new BackgroundComponent();
	// top-left of the panel on the canvas, from the last render
	private Point origin;

	@Inject
	StatusBarOverlay(Client client, AfkMiniModePlugin plugin, AfkMiniModeConfig config,
		SkillIconManager skillIcons, SpriteManager sprites, ItemManager items, TooltipManager tooltipManager)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.skillIcons = skillIcons;
		this.sprites = sprites;
		this.items = items;
		this.tooltipManager = tooltipManager;
		background.setBackgroundColor(ComponentConstants.STANDARD_BACKGROUND_COLOR);
		// placed by hand at the canvas bottom-left: RuneLite's BOTTOM_LEFT anchor follows the (hidden) chatbox and drifts
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	// the game's XP counter (moved top-left) replaces the skill row when XP drops aren't hidden
	private boolean showSkillRow()
	{
		return config.showSkill() && config.hideXpDrops();
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!plugin.isActive() || !config.showBar())
		{
			return null;
		}

		int w = config.barWidth();
		int h = config.barHeight();
		int inner = w - 2 * PAD;

		int containers = 0;
		if (config.showContainers())
		{
			for (StorageTracker t : plugin.getTrackers())
			{
				if (t.collectingItemId() != -1)
				{
					containers++;
				}
			}
		}
		int rows = (config.showInventory() ? 1 : 0) + containers;

		int contentHeight = (showSkillRow() ? h + SKILL_BAR_HEIGHT + GAP : 0) + rows * (h + GAP);
		if (contentHeight == 0)
		{
			return null;
		}
		contentHeight -= GAP;
		Dimension size = new Dimension(w, contentHeight + 2 * PAD);

		origin = new Point(MARGIN, client.getCanvasHeight() - size.height - MARGIN);
		g.translate(origin.x, origin.y);
		try
		{
			drawPanel(g, size, w, h, inner);
		}
		finally
		{
			g.translate(-origin.x, -origin.y);
		}
		return null;
	}

	private void drawPanel(Graphics2D g, Dimension size, int w, int h, int inner)
	{
		background.setRectangle(new Rectangle(size));
		background.render(g);
		g.setFont(FontManager.getRunescapeSmallFont());

		Point mouse = localMouse();
		int y = PAD;
		if (showSkillRow())
		{
			drawSkill(g, PAD, y, inner, h);
			if (hovered(mouse, y, h + SKILL_BAR_HEIGHT))
			{
				tooltip(skillTooltip());
			}
			y += h + SKILL_BAR_HEIGHT + GAP;
		}
		if (config.showInventory())
		{
			drawInventory(g, PAD, y, inner, h);
			if (hovered(mouse, y, h))
			{
				tooltip(inventoryTooltip());
			}
			y += h + GAP;
		}
		if (config.showContainers())
		{
			for (StorageTracker t : plugin.getTrackers())
			{
				int itemId = t.collectingItemId();
				if (itemId != -1)
				{
					drawContainer(g, PAD, y, inner, h, t, itemId);
					if (hovered(mouse, y, h))
					{
						tooltip(containerTooltip(t, itemId));
					}
					y += h + GAP;
				}
			}
		}
	}

	// mouse position relative to the panel, or null when it's off the canvas
	private Point localMouse()
	{
		net.runelite.api.Point m = client.getMouseCanvasPosition();
		if (m == null || origin == null || m.getX() < 0)
		{
			return null;
		}
		return new Point(m.getX() - origin.x, m.getY() - origin.y);
	}

	private boolean hovered(Point mouse, int y, int h)
	{
		return mouse != null && mouse.x >= 0 && mouse.x < config.barWidth() && mouse.y >= y && mouse.y < y + h;
	}

	private void tooltip(String text)
	{
		if (text != null)
		{
			tooltipManager.add(new Tooltip(text));
		}
	}

	private String skillTooltip()
	{
		Skill s = plugin.getLastSkill();
		if (s == null)
		{
			return "Shows the skill you last gained XP in";
		}
		int xp = client.getSkillExperience(s);
		int level = Experience.getLevelForXp(xp);
		StringBuilder sb = new StringBuilder()
			.append(ColorUtil.wrapWithColorTag(s.getName(), JagexColors.MENU_TARGET)).append(" level ").append(level)
			.append("</br>Current XP: ").append(QuantityFormatter.formatNumber(xp));
		if (level < Experience.MAX_REAL_LEVEL)
		{
			sb.append("</br>XP to ").append(level + 1).append(": ").append(QuantityFormatter.formatNumber(Experience.getXpForLevel(level + 1) - xp))
				.append(" (").append((int) (100 * levelProgress(xp))).append("% done)");
		}
		return sb.toString();
	}

	private String inventoryTooltip()
	{
		ItemContainer c = client.getItemContainer(InventoryID.INV);
		// item name -> total quantity, in inventory order
		Map<String, Integer> counts = new LinkedHashMap<>();
		if (c != null)
		{
			for (Item i : c.getItems())
			{
				if (i.getId() != -1)
				{
					counts.merge(items.getItemComposition(i.getId()).getName(), i.getQuantity(), Integer::sum);
				}
			}
		}
		StringBuilder sb = new StringBuilder(ColorUtil.wrapWithColorTag("Inventory", JagexColors.MENU_TARGET))
			.append(' ').append(usedSlots()).append('/').append(INVENTORY_SIZE).append(" slots used");
		counts.forEach((name, qty) -> sb.append("</br>").append(name).append(" x ").append(QuantityFormatter.formatNumber(qty)));
		return sb.toString();
	}

	private String containerTooltip(StorageTracker t, int itemId)
	{
		StringBuilder sb = new StringBuilder(ColorUtil.wrapWithColorTag(items.getItemComposition(itemId).getName(), JagexColors.MENU_TARGET));
		if (!t.isKnown())
		{
			return sb.append("</br>Count unknown: use Check on it").toString();
		}
		return sb.append("</br>Holding ").append(t.getHeld()).append(" / ").append(StorageTracker.CAPACITY)
			.append("</br>").append(StorageTracker.CAPACITY - t.getHeld()).append(" free").toString();
	}

	// like the game's XP counter: icon left, total XP right, thin bar to the next level underneath
	private void drawSkill(Graphics2D g, int x, int y, int w, int h)
	{
		Skill s = plugin.getLastSkill();
		if (s == null)
		{
			drawText(g, "No XP yet", x, y, w, h, Color.WHITE, false);
			fillBar(g, x, y + h, w, SKILL_BAR_HEIGHT, 0, config.skillColor());
			return;
		}

		int xp = client.getSkillExperience(s);
		drawIcon(g, skillIcons.getSkillImage(s, true), x, y, h);
		drawText(g, QuantityFormatter.formatNumber(xp), x, y, w, h, Color.WHITE, true);
		fillBar(g, x, y + h, w, SKILL_BAR_HEIGHT, levelProgress(xp), config.skillColor());
	}

	// 0..1 of the way to the next level; 1 at 99
	private static double levelProgress(int xp)
	{
		int level = Experience.getLevelForXp(xp);
		if (level >= Experience.MAX_REAL_LEVEL)
		{
			return 1;
		}
		int start = Experience.getXpForLevel(level);
		return (double) (xp - start) / (Experience.getXpForLevel(level + 1) - start);
	}

	private void drawInventory(Graphics2D g, int x, int y, int w, int h)
	{
		drawCapacityRow(g, x, y, w, h, sprites.getSprite(SpriteID.SideIcons.INVENTORY, 0), usedSlots(), INVENTORY_SIZE);
	}

	private int usedSlots()
	{
		ItemContainer c = client.getItemContainer(InventoryID.INV);
		return c == null ? 0 : c.count();
	}

	private void drawContainer(Graphics2D g, int x, int y, int w, int h, StorageTracker t, int itemId)
	{
		Image icon = items.getImage(itemId);
		if (!t.isKnown())
		{
			// count unknown until an exact reading
			drawRow(g, x, y, w, h, icon, 0, config.inventoryColor(), "Check " + t.getName(), CHECK_TEXT);
			return;
		}
		drawCapacityRow(g, x, y, w, h, icon, t.getHeld(), StorageTracker.CAPACITY);
	}

	private void drawCapacityRow(Graphics2D g, int x, int y, int w, int h, Image icon, int used, int capacity)
	{
		int free = capacity - used;
		drawRow(g, x, y, w, h, icon, (double) used / capacity,
			free == 0 ? config.fullColor() : config.inventoryColor(),
			free == 0 ? "Full" : free + " free", Color.WHITE);
	}

	private void drawRow(Graphics2D g, int x, int y, int w, int h, Image icon, double ratio, Color fill, String text, Color textColor)
	{
		drawIcon(g, icon, x, y, h);
		int bx = x + h + GAP;
		int bw = w - h - GAP;
		fillBar(g, bx, y, bw, h, ratio, fill);
		if (config.barText())
		{
			drawText(g, text, bx, y, bw, h, textColor, false);
		}
	}

	private void drawIcon(Graphics2D g, Image icon, int x, int y, int size)
	{
		if (icon == null || icon.getWidth(null) <= 0 || icon.getHeight(null) <= 0)
		{
			return;
		}
		int iw = icon.getWidth(null);
		int ih = icon.getHeight(null);
		double scale = Math.min((double) size / iw, (double) size / ih);
		int dw = (int) (iw * scale);
		int dh = (int) (ih * scale);
		g.drawImage(icon, x + (size - dw) / 2, y + (size - dh) / 2, dw, dh, null);
	}

	private static void fillBar(Graphics2D g, int x, int y, int w, int h, double ratio, Color fill)
	{
		g.setColor(BAR_BACKGROUND);
		g.fillRect(x, y, w, h);
		g.setColor(fill);
		g.fillRect(x, y, (int) (w * Math.max(0, Math.min(1, ratio))), h);
	}

	private static void drawText(Graphics2D g, String text, int x, int y, int w, int h, Color color, boolean rightAlign)
	{
		FontMetrics fm = g.getFontMetrics();
		int tx = rightAlign ? x + w - fm.stringWidth(text) - 2 : x + (w - fm.stringWidth(text)) / 2;
		int ty = y + (h + fm.getAscent() - fm.getDescent()) / 2;
		g.setColor(Color.BLACK);
		g.drawString(text, tx + 1, ty + 1);
		g.setColor(color);
		g.drawString(text, tx, ty);
	}
}
