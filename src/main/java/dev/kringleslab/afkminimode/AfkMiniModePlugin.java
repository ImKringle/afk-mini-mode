package dev.kringleslab.afkminimode;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
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
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
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
	tags = {"mini", "afk", "compact", "small", "window", "hide", "minimap", "chat", "barrel", "basket", "coal", "gem", "motherlode"}
)
public class AfkMiniModePlugin extends Plugin
{
	private static final String RUNELITE_OPACITY_KEY = "uiWindowOpacity";
	// pre-mini-mode window settings, kept in our config while mini mode is on. Still there at startup = the client
	// was killed or crashed in mini mode (no ClientShutdown), so they get restored then.
	private static final String RESTORE_BOUNDS = "restoreBounds";
	private static final String RESTORE_STATE = "restoreState";
	private static final String RESTORE_ON_TOP = "restoreOnTop";
	private static final String RESTORE_OPACITY = "restoreOpacity";

	private static final int[] MINIMAP = {InterfaceID.ToplevelOsrsStretch.MAP_MINIMAP, InterfaceID.ToplevelPreEoc.MAP_MINIMAP};
	private static final int[] ORBS = {InterfaceID.ToplevelOsrsStretch.ORBS, InterfaceID.ToplevelPreEoc.ORBS};
	// MAINCRM: Jagex promo banners (e.g. "Have your say... click here to vote") that sit above the chat
	private static final int[] CHAT = {InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER, InterfaceID.ToplevelPreEoc.CHAT_CONTAINER,
		InterfaceID.ToplevelOsrsStretch.MAINCRM, InterfaceID.ToplevelPreEoc.MAINCRM};
	// RuneLite's movable overlay wrapping the game's XP counter
	private static final String XP_TRACKER_OVERLAY = "EXPERIENCE_TRACKER_WIDGET";
	private static final int[] XP_DROPS = {InterfaceID.ToplevelOsrsStretch.XP_DROPS, InterfaceID.ToplevelPreEoc.XP_DROPS};
	// the game's pay-dirt sack counter; the status panel has its own row for it
	private static final int[] MOTHERLODE_HUD = {InterfaceID.MotherlodeHud.UNIVERSE};

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
	private CoalBagTracker coalBag;

	@Inject
	private GemBagTracker gemBag;

	@Inject
	private AfkMiniModeConfig config;

	// toggled on the EDT, read on the client thread
	@Getter
	private volatile boolean active;

	// client thread only: widgets this plugin hid, so only those are ever unhidden
	private final Set<Integer> hiddenByUs = new HashSet<>();

	// client thread only: item IDs carried, shared by the storage trackers
	private final Multiset<Integer> inventory = HashMultiset.create();
	private final Multiset<Integer> equipment = HashMultiset.create();

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
		for (StorageTracker t : getTrackers())
		{
			t.start(inventory, equipment);
			eventBus.register(t);
		}
		clientThread.invokeLater(this::refreshSnapshots);
		recoverFromKill();
	}

	private void recoverFromKill()
	{
		Integer opacity = configManager.getConfiguration(AfkMiniModeConfig.GROUP, RESTORE_OPACITY, Integer.class);
		if (opacity == null)
		{
			return;
		}
		log.debug("Last session ended in mini mode without leaving it; restoring window settings");
		Integer state = configManager.getConfiguration(AfkMiniModeConfig.GROUP, RESTORE_STATE, Integer.class);
		savedBounds = configManager.getConfiguration(AfkMiniModeConfig.GROUP, RESTORE_BOUNDS, Rectangle.class);
		savedState = state == null ? Frame.NORMAL : state;
		savedOnTop = Boolean.TRUE.equals(configManager.getConfiguration(AfkMiniModeConfig.GROUP, RESTORE_ON_TOP, Boolean.class));
		savedOpacity = opacity;
		SwingUtilities.invokeLater(() ->
		{
			writeRuneLiteConfig();
			Frame frame = frame();
			if (frame != null && savedBounds != null)
			{
				frame.setBounds(savedBounds);
				frame.setExtendedState(savedState);
			}
			clearRestorePoint();
		});
	}

	private void saveRestorePoint()
	{
		configManager.setConfiguration(AfkMiniModeConfig.GROUP, RESTORE_BOUNDS, savedBounds);
		configManager.setConfiguration(AfkMiniModeConfig.GROUP, RESTORE_STATE, savedState);
		configManager.setConfiguration(AfkMiniModeConfig.GROUP, RESTORE_ON_TOP, savedOnTop);
		configManager.setConfiguration(AfkMiniModeConfig.GROUP, RESTORE_OPACITY, savedOpacity);
	}

	private void clearRestorePoint()
	{
		for (String key : new String[]{RESTORE_BOUNDS, RESTORE_STATE, RESTORE_ON_TOP, RESTORE_OPACITY})
		{
			configManager.unsetConfiguration(AfkMiniModeConfig.GROUP, key);
		}
	}

	// RuneLite's own settings, as they were before mini mode; RuneLite reads the bounds/maximized ones on next launch
	private void writeRuneLiteConfig()
	{
		if (savedBounds != null)
		{
			configManager.setConfiguration(RuneLiteConfig.GROUP_NAME, "clientBounds", savedBounds);
		}
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
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(hotkey);
		keyManager.unregisterKeyListener(opacityHotkey);
		overlayManager.remove(statusBar);
		getTrackers().forEach(eventBus::unregister);
		SwingUtilities.invokeLater(() -> setActive(false));
	}

	List<StorageTracker> getTrackers()
	{
		return List.of(fishBarrel, logBasket, coalBag, gemBag);
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
		// null before the client UI exists (plugin started during launch)
		return client.getCanvas() == null ? null : (Frame) SwingUtilities.getWindowAncestor(client.getCanvas());
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
		writeRuneLiteConfig();
		rememberDraggedPosition(frame());
		clearRestorePoint();
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

	// the first StatChanged per skill (the login burst) only seeds lastXp; later increases are real XP drops
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
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(this::refreshSnapshots);
		}
		else if (e.getGameState() == GameState.LOGIN_SCREEN)
		{
			lastXp.clear();
			lastSkill = null;
			inventory.clear();
			equipment.clear();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		if (e.getContainerId() == InventoryID.INV)
		{
			// one shared snapshot for all trackers; each gets the before/after diff to work out what moved
			Multiset<Integer> prev = HashMultiset.create(inventory);
			snapshot(e.getItemContainer(), inventory);
			for (StorageTracker t : getTrackers())
			{
				if (t.hasContainer())
				{
					t.inventoryChanged(prev);
				}
			}
		}
		else if (e.getContainerId() == InventoryID.WORN)
		{
			snapshot(e.getItemContainer(), equipment);
		}
	}

	// one entry per slot, so a stack counts once (quantity ignored); fine for the unstackable fish/logs/coal/gems tracked
	private static void snapshot(ItemContainer container, Multiset<Integer> target)
	{
		target.clear();
		for (Item i : container.getItems())
		{
			target.add(i.getId());
		}
	}

	// ItemContainerChanged only fires on change, so read the containers directly when starting while logged in or on login
	private void refreshSnapshots()
	{
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (inv != null)
		{
			snapshot(inv, inventory);
		}
		if (worn != null)
		{
			snapshot(worn, equipment);
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
			saveRestorePoint();
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
			// each step on its own, so one failure can't leave plugins paused or the window tiny
			safely("restore the XP counter", this::restoreXpTracker);
			safely("restore info panels", this::restorePanels);
			safely("resume plugins", this::resumePlugins);
			safely("restore opacity", () -> configManager.setConfiguration(RuneLiteConfig.GROUP_NAME, RUNELITE_OPACITY_KEY, savedOpacity));
			safely("save the dragged position", () -> rememberDraggedPosition(frame));
			safely("restore always on top", () -> configManager.setConfiguration(RuneLiteConfig.GROUP_NAME, "gameAlwaysOnTop", savedOnTop));
			safely("restore the window", () ->
			{
				frame.setBounds(savedBounds);
				frame.setExtendedState(savedState);
			});
			clearRestorePoint();
		}

		// BeforeRender stops firing once the plugin is off, so apply directly too
		clientThread.invokeLater(this::applyHiding);
	}

	private static void safely(String what, Runnable step)
	{
		try
		{
			step.run();
		}
		catch (RuntimeException ex)
		{
			log.warn("Couldn't {} when leaving mini mode", what, ex);
		}
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

	// a remembered position can be on a monitor that's since been unplugged
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

	// every frame: the game rebuilds these widgets (layout change, interface reload) and they come back visible
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
		setHidden(MOTHERLODE_HUD, on && config.showBar() && config.showContainers());
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
				// already hidden by the game or another plugin: leave it out of hiddenByUs so we never unhide it
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
