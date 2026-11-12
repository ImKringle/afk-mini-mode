package dev.kringleslab.afkminimode;

import com.google.inject.Provides;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.infobox.InfoBoxOverlay;
import net.runelite.client.util.HotkeyListener;

@Slf4j
@PluginDescriptor(
	name = "AFK Mini Mode",
	description = "One hotkey shrinks RuneLite to a small always-on-top window with a skilling status bar",
	tags = {"mini", "afk", "compact", "small", "window", "hide", "minimap", "chat", "barrel", "basket"}
)
public class AfkMiniModePlugin extends Plugin
{
	private static final String RUNELITE_OPACITY_KEY = "uiWindowOpacity";

	private static final int[] MINIMAP = {InterfaceID.ToplevelOsrsStretch.MAP_MINIMAP, InterfaceID.ToplevelPreEoc.MAP_MINIMAP};
	private static final int[] ORBS = {InterfaceID.ToplevelOsrsStretch.ORBS, InterfaceID.ToplevelPreEoc.ORBS};
	// MAINCRM: Jagex promo banners (e.g. "Have your say... click here to vote") that sit above the chat
	private static final int[] CHAT = {InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER, InterfaceID.ToplevelPreEoc.CHAT_CONTAINER,
		InterfaceID.ToplevelOsrsStretch.MAINCRM, InterfaceID.ToplevelPreEoc.MAINCRM};
	// RuneLite's movable overlay wrapping the game's XP counter
	private static final String XP_TRACKER_OVERLAY = "EXPERIENCE_TRACKER_WIDGET";
	private static final int[] XP_DROPS = {InterfaceID.ToplevelOsrsStretch.XP_DROPS, InterfaceID.ToplevelPreEoc.XP_DROPS};

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private RuneLiteConfig runeLiteConfig;

	@Inject
	private KeyManager keyManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private StatusBarOverlay statusBar;

	@Inject
	private FishBarrelTracker fishBarrel;

	@Inject
	private LogBasketTracker logBasket;

	@Inject
	private AfkMiniModeConfig config;

	// toggled on the EDT, read on the client thread
	@Getter
	private volatile boolean active;

	// client thread only: widgets this plugin hid, so only those are ever unhidden
	private final Set<Integer> hiddenByUs = new HashSet<>();

	// client thread only: the skill that last gained XP, for the status bar
	private final Map<Skill, Integer> lastXp = new EnumMap<>(Skill.class);
	@Getter
	private Skill lastSkill;

	// EDT only: what to put back on exit
	private Rectangle savedBounds;
	private int savedState;
	private boolean savedOnTop;
	private int savedOpacity;
	private Point placedAt;
	private final List<Plugin> pausedByUs = new ArrayList<>();
	private final List<Overlay> hiddenPanels = new ArrayList<>();
	private Overlay xpTracker;
	private OverlayPosition prevXpPosition;
	private Point prevXpLocation;

	private final HotkeyListener hotkey = new HotkeyListener(() -> config.toggleKey())
	{
		@Override
		public void hotkeyPressed()
		{
			SwingUtilities.invokeLater(() -> setActive(!active));
		}
	};

	// EDT only: opacity hotkey has switched to fully solid
	private boolean solid;

	private final HotkeyListener opacityHotkey = new HotkeyListener(() -> config.opacityToggleKey())
	{
		@Override
		public void hotkeyPressed()
		{
			SwingUtilities.invokeLater(() ->
			{
				if (active)
				{
					solid = !solid;
					applyOpacity();
				}
			});
		}
	};

	@Provides
	AfkMiniModeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AfkMiniModeConfig.class);
	}

	@Override
	protected void startUp()
	{
		keyManager.registerKeyListener(hotkey);
		keyManager.registerKeyListener(opacityHotkey);
		overlayManager.add(statusBar);
		fishBarrel.start();
		logBasket.start();
		eventBus.register(fishBarrel);
		eventBus.register(logBasket);
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(hotkey);
		keyManager.unregisterKeyListener(opacityHotkey);
		overlayManager.remove(statusBar);
		eventBus.unregister(fishBarrel);
		eventBus.unregister(logBasket);
		SwingUtilities.invokeLater(() -> setActive(false));
	}

	List<StorageTracker> getTrackers()
	{
		return List.of(fishBarrel, logBasket);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!AfkMiniModeConfig.GROUP.equals(e.getGroup()))
		{
			return;
		}
		switch (e.getKey())
		{
			case "hideXpDrops":
				// apply live: the panel's skill row already follows this setting each frame
				if (active)
				{
					SwingUtilities.invokeLater(() ->
					{
						restoreXpTracker();
						if (!config.hideXpDrops())
						{
							moveXpTracker();
						}
					});
				}
				break;
			case "opacity":
				// live, so it can be tuned while watching something behind it
				if (active)
				{
					SwingUtilities.invokeLater(this::applyOpacity);
				}
				break;
			case "corner":
			case "monitor":
			case "paddingX":
			case "paddingY":
				// picking a corner, monitor or padding forgets the dragged position
				configManager.unsetConfiguration(AfkMiniModeConfig.GROUP, "savedX");
				configManager.unsetConfiguration(AfkMiniModeConfig.GROUP, "savedY");
				if (active)
				{
					SwingUtilities.invokeLater(() ->
					{
						Frame frame = frame();
						frame.setLocation(cornerLocation(frame, frame.getSize()));
						placedAt = frame.getLocation();
					});
				}
				break;
		}
	}

	private Frame frame()
	{
		return (Frame) SwingUtilities.getWindowAncestor(client.getCanvas());
	}

	// RuneLite saves the window bounds *before* posting ClientShutdown, so a client closed in mini mode would reopen tiny.
	// Overwrite what it saved with the pre-mini-mode state (runs on the EDT, same as setActive).
	@Subscribe
	public void onClientShutdown(ClientShutdown e)
	{
		if (!active)
		{
			return;
		}
		configManager.setConfiguration(RuneLiteConfig.GROUP_NAME, "clientBounds", savedBounds);
		if ((savedState & Frame.MAXIMIZED_BOTH) != 0)
		{
			configManager.setConfiguration(RuneLiteConfig.GROUP_NAME, "clientMaximized", true);
		}
		else
		{
			configManager.unsetConfiguration(RuneLiteConfig.GROUP_NAME, "clientMaximized");
		}
		configManager.setConfiguration(RuneLiteConfig.GROUP_NAME, "gameAlwaysOnTop", savedOnTop);
		configManager.setConfiguration(RuneLiteConfig.GROUP_NAME, RUNELITE_OPACITY_KEY, savedOpacity);
		rememberDraggedPosition(frame());
	}

	private void rememberDraggedPosition(Frame frame)
	{
		Point now = frame.getLocation();
		if (config.rememberPosition() && !now.equals(placedAt))
		{
			configManager.setConfiguration(AfkMiniModeConfig.GROUP, "savedX", now.x);
			configManager.setConfiguration(AfkMiniModeConfig.GROUP, "savedY", now.y);
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		Integer old = lastXp.put(e.getSkill(), e.getXp());
		if (old != null && e.getXp() > old)
		{
			lastSkill = e.getSkill();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGIN_SCREEN)
		{
			lastXp.clear();
			lastSkill = null;
		}
	}

	private void setActive(boolean on)
	{
		if (on == active)
		{
			return;
		}
		Frame frame = frame();
		active = on;

		if (on)
		{
			savedBounds = frame.getBounds();
			savedState = frame.getExtendedState();
			savedOnTop = runeLiteConfig.gameAlwaysOnTop();
			savedOpacity = runeLiteConfig.windowOpacity();
			if (config.alwaysOnTop())
			{
				configManager.setConfiguration(RuneLiteConfig.GROUP_NAME, "gameAlwaysOnTop", true);
			}

			frame.setExtendedState(Frame.NORMAL);
			// RuneLite keeps the frame's minimum size at the smallest legal client size
			Dimension size = frame.getMinimumSize();
			Point at = new Point(config.savedX(), config.savedY());
			if (!config.rememberPosition() || at.x == Integer.MIN_VALUE || !isOnScreen(at))
			{
				at = cornerLocation(frame, size);
			}
			frame.setBounds(at.x, at.y, size.width, size.height);
			placedAt = frame.getLocation();
			solid = false;
			applyOpacity();
			pausePlugins();
			if (config.hidePanels())
			{
				hidePanels();
			}
			if (!config.hideXpDrops())
			{
				moveXpTracker();
			}
		}
		else
		{
			restoreXpTracker();
			restorePanels();
			resumePlugins();
			configManager.setConfiguration(RuneLiteConfig.GROUP_NAME, RUNELITE_OPACITY_KEY, savedOpacity);
			rememberDraggedPosition(frame);
			configManager.setConfiguration(RuneLiteConfig.GROUP_NAME, "gameAlwaysOnTop", savedOnTop);
			frame.setBounds(savedBounds);
			frame.setExtendedState(savedState);
		}

		// BeforeRender stops firing once the plugin is off, so apply directly too
		clientThread.invokeLater(this::applyHiding);
	}

	// via RuneLite's own Window opacity setting: it re-applies that on every window config change (e.g. sidebar toggle),
	// so setting the frame directly would get overwritten
	private void applyOpacity()
	{
		configManager.setConfiguration(RuneLiteConfig.GROUP_NAME, RUNELITE_OPACITY_KEY, solid ? 100 : config.opacity());
	}

	private Point cornerLocation(Frame frame, Dimension size)
	{
		GraphicsConfiguration gc = frame.getGraphicsConfiguration();
		GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
		GraphicsDevice[] screens = env.getScreenDevices();
		int monitor = config.monitor().getIndex();
		if (monitor == -1)
		{
			gc = env.getDefaultScreenDevice().getDefaultConfiguration();
		}
		else if (monitor > 0 && monitor <= screens.length)
		{
			gc = screens[monitor - 1].getDefaultConfiguration();
		}
		Rectangle screen = gc.getBounds();
		Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
		AfkMiniModeConfig.Corner c = config.corner();
		int x = c.isRight()
			? screen.x + screen.width - in.right - size.width - config.paddingX()
			: screen.x + in.left + config.paddingX();
		int y = c.isBottom()
			? screen.y + screen.height - in.bottom - size.height - config.paddingY()
			: screen.y + in.top + config.paddingY();
		return new Point(x, y);
	}

	private static boolean isOnScreen(Point p)
	{
		for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
		{
			if (d.getDefaultConfiguration().getBounds().contains(p))
			{
				return true;
			}
		}
		return false;
	}

	// panels and infoboxes; world-space overlays (DYNAMIC, e.g. fishing spot highlights) and tooltips stay
	private boolean isPanel(Overlay o)
	{
		if (o.getPlugin() == this)
		{
			return false;
		}
		if (o instanceof InfoBoxOverlay)
		{
			return true;
		}
		OverlayPosition p = o.getPosition();
		return o.getPlugin() != null && p != OverlayPosition.DYNAMIC && p != OverlayPosition.TOOLTIP;
	}

	private void hidePanels()
	{
		overlayManager.removeIf(o ->
		{
			if (isPanel(o))
			{
				hiddenPanels.add(o);
				return true;
			}
			return false;
		});
	}

	private void restorePanels()
	{
		for (Overlay o : hiddenPanels)
		{
			// skip overlays whose plugin was turned off meanwhile
			if (o.getPlugin() == null || pluginManager.isPluginActive(o.getPlugin()))
			{
				overlayManager.add(o);
			}
		}
		hiddenPanels.clear();
	}

	// same as Alt-dragging the XP counter to the top-left snap corner, but not saved
	private void moveXpTracker()
	{
		overlayManager.anyMatch(o ->
		{
			if (XP_TRACKER_OVERLAY.equals(o.getName()))
			{
				xpTracker = o;
				return true;
			}
			return false;
		});
		if (xpTracker == null)
		{
			log.debug("XP counter overlay {} not found", XP_TRACKER_OVERLAY);
		}
		else
		{
			prevXpPosition = xpTracker.getPreferredPosition();
			prevXpLocation = xpTracker.getPreferredLocation();
			xpTracker.setPreferredLocation(null);
			xpTracker.setPreferredPosition(OverlayPosition.TOP_LEFT);
		}
	}

	private void restoreXpTracker()
	{
		if (xpTracker != null)
		{
			xpTracker.setPreferredPosition(prevXpPosition);
			xpTracker.setPreferredLocation(prevXpLocation);
			xpTracker = null;
		}
	}

	// "Ground Items" matches GroundItemsPlugin, "NPC Indicators" matches NpcIndicatorsPlugin, ...
	private static String pluginKey(String name)
	{
		return name.replaceAll("Plugin$", "").replaceAll("[^A-Za-z0-9]", "").toLowerCase();
	}

	// ponytail: matches by class name, which is the display name for core plugins; some hub plugins name classes differently
	private void pausePlugins()
	{
		Set<String> wanted = new HashSet<>();
		for (String n : config.pausePlugins().split(","))
		{
			String k = pluginKey(n.trim());
			if (!k.isEmpty())
			{
				wanted.add(k);
			}
		}

		for (Plugin p : pluginManager.getPlugins())
		{
			if (p != this && wanted.contains(pluginKey(p.getClass().getSimpleName())) && pluginManager.isPluginActive(p))
			{
				try
				{
					if (pluginManager.stopPlugin(p))
					{
						pausedByUs.add(p);
					}
				}
				catch (PluginInstantiationException ex)
				{
					log.warn("Couldn't pause {}", p.getClass().getSimpleName(), ex);
				}
			}
		}
	}

	private void resumePlugins()
	{
		for (Plugin p : pausedByUs)
		{
			// skip anything the user turned off meanwhile
			if (pluginManager.isPluginEnabled(p) && !pluginManager.isPluginActive(p))
			{
				try
				{
					pluginManager.startPlugin(p);
				}
				catch (PluginInstantiationException ex)
				{
					log.warn("Couldn't resume {}", p.getClass().getSimpleName(), ex);
				}
			}
		}
		pausedByUs.clear();
	}

	@Subscribe
	public void onBeforeRender(BeforeRender e)
	{
		applyHiding();
	}

	private void applyHiding()
	{
		boolean on = active;
		setHidden(MINIMAP, on && config.hideMinimap());
		setHidden(ORBS, on && config.hideOrbs());
		setHidden(CHAT, on && config.hideChat());
		setHidden(XP_DROPS, on && config.hideXpDrops());
	}

	private void setHidden(int[] ids, boolean hide)
	{
		for (int id : ids)
		{
			Widget w = client.getWidget(id);
			if (w == null)
			{
				continue;
			}
			if (hide)
			{
				if (!w.isSelfHidden())
				{
					w.setHidden(true);
					hiddenByUs.add(id);
				}
			}
			else if (hiddenByUs.remove(id))
			{
				// never unhide a minimap the game itself has hidden
				boolean gameHidesMinimap = client.getVarbitValue(VarbitID.MINIMAP_TOGGLE) == 1
					&& (id == MINIMAP[0] || id == MINIMAP[1]);
				if (!gameHidesMinimap)
				{
					w.setHidden(false);
				}
			}
		}
	}
}
