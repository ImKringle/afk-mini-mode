/*
 * Copyright (c) 2024, aidanmastro
 * All rights reserved.
 *
 * Ported from the Log Basket plugin (https://github.com/aidanmastro/log-basket-plugin) into AFK Mini Mode,
 * updated to gameval constants and to only treat the count as known after an exact reading.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package dev.kringleslab.afkminimode;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Log basket and forestry basket. An open basket catches chopped logs (counted per tick from chat); an infernal axe
 * burns some of them, seen as firemaking XP.
 */
public class LogBasketTracker extends StorageTracker
{
	private static final int GAME_TICK_MARGIN = 2;

	private static final String FULL_BASKET_MESSAGE = "The basket is full.";
	private static final String EMPTY_BASKET_MESSAGE = "Your basket is empty.";
	private static final String CHECKED_EMPTY_BASKET_MESSAGE = "The basket is empty.";
	private static final String EMPTIED_TO_BANK_MESSAGE = "You empty your basket into the bank.";
	private static final String EMPTIED_ALL_TO_INVENTORY_MESSAGE = "You empty your basket.";
	private static final String EMPTIED_PARTIAL_TO_INVENTORY_MESSAGE = "You empty as many logs as you can carry.";
	private static final String LOG_CUT_KANDARIN_HEADGEAR_MESSAGE = "Your Kandarin headgear provides you with an additional log.";
	private static final String LOG_CUT_NATURE_OFFERINGS_MESSAGE = "The nature offerings enabled you to chop an extra log.";
	private static final String LOG_CUT_MESSAGE_PREFIX = "You get some ";

	private static final Pattern CHECK_ENTRY_PATTERN = Pattern.compile("(\\d+)\\s*[×x]\\s*([^,<\\n]+)", Pattern.CASE_INSENSITIVE);

	private static final Set<String> LOG_NAMES = ImmutableSet.of(
		"logs", "achey tree logs", "oak logs", "willow logs", "teak logs", "maple logs", "mahogany logs",
		"arctic pine logs", "yew logs", "magic logs", "redwood logs", "rosewood logs"
	);

	private static final Set<Integer> LOG_IDS = ImmutableSet.of(
		ItemID.LOGS,
		ItemID.ACHEY_TREE_LOGS,
		ItemID.OAK_LOGS,
		ItemID.WILLOW_LOGS,
		ItemID.TEAK_LOGS,
		ItemID.MAPLE_LOGS,
		ItemID.MAHOGANY_LOGS,
		ItemID.ARCTIC_PINE_LOG,
		ItemID.YEW_LOGS,
		ItemID.MAGIC_LOGS,
		ItemID.REDWOOD_LOGS,
		ItemID.ROSEWOOD_LOGS
	);

	private static final ImmutableList<Integer> BASKET_IDS = ImmutableList.of(
		ItemID.LOG_BASKET_OPEN,
		ItemID.LOG_BASKET_CLOSED,
		ItemID.FORESTRY_BASKET_OPEN,
		ItemID.FORESTRY_BASKET_CLOSED
	);

	private static final ImmutableList<Integer> OPEN_BASKET_IDS = ImmutableList.of(
		ItemID.LOG_BASKET_OPEN,
		ItemID.FORESTRY_BASKET_OPEN
	);

	private static final ImmutableList<Integer> CHARGED_INFERNAL_AXE_IDS = ImmutableList.of(
		ItemID.INFERNAL_AXE,
		ItemID.TRAILBLAZER_AXE,
		ItemID.TRAILBLAZER_RELOADED_AXE
	);

	// per-tick counters, reset every GameTick
	private int newLogs;
	private int burnedLogs;

	private boolean pendingPartialEmpty;

	@Inject
	LogBasketTracker(Client client, ClientThread clientThread)
	{
		super(client, clientThread, "basket", GAME_TICK_MARGIN, BASKET_IDS, OPEN_BASKET_IDS, LogBasketTracker::parseCheck);
	}

	@Override
	protected void reset()
	{
		super.reset();
		pendingPartialEmpty = false;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		String message = event.getMessage();

		if (event.getType() == ChatMessageType.GAMEMESSAGE && hasContainer())
		{
			switch (message)
			{
				case EMPTIED_TO_BANK_MESSAGE:
				case EMPTIED_CONTAINERS_TO_BANK_MESSAGE:
				case CONTAINERS_ALREADY_EMPTY_MESSAGE:
				case EMPTIED_ALL_TO_INVENTORY_MESSAGE:
				case EMPTY_BASKET_MESSAGE:
				case CHECKED_EMPTY_BASKET_MESSAGE:
					setExact(0);
					return;
				case FULL_BASKET_MESSAGE:
					setExact(getCapacity());
					return;
				case EMPTIED_PARTIAL_TO_INVENTORY_MESSAGE:
					pendingPartialEmpty = isKnown();
					return;
			}
		}

		if (event.getType() == ChatMessageType.SPAM && isCollecting())
		{
			if (message.startsWith(LOG_CUT_MESSAGE_PREFIX)
				|| message.equals(LOG_CUT_KANDARIN_HEADGEAR_MESSAGE)
				|| message.equals(LOG_CUT_NATURE_OFFERINGS_MESSAGE))
			{
				newLogs++;
			}
		}
	}

	@Override
	protected void inventoryChanged(Multiset<Integer> prev)
	{
		// partial empty: whatever logs the inventory gained came out of the basket
		if (pendingPartialEmpty)
		{
			pendingPartialEmpty = false;
			int moved = (int) (inventory.stream().filter(LOG_IDS::contains).count()
				- prev.stream().filter(LOG_IDS::contains).count());
			addIfKnown(-moved);
		}
		else if (!Multisets.difference(prev, inventory).isEmpty() && consumeAction(Action.FILL))
		{
			addIfKnown(Multisets.filter(Multisets.difference(prev, inventory), LOG_IDS::contains).size());
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		Widget w = event.getWidget();
		switch (event.getMenuAction())
		{
			case CC_OP:
			case CC_OP_LOW_PRIORITY:
				break;
			case WIDGET_TARGET_ON_WIDGET:
				// using logs on the basket
				if (w != null && BASKET_IDS.contains(w.getItemId()))
				{
					markAction(Action.FILL);
				}
				return;
			default:
				return;
		}

		Action action;
		switch (event.getMenuOption().toLowerCase())
		{
			case "fill":
				action = Action.FILL;
				break;
			case "empty":
				action = Action.EMPTY;
				break;
			case "check":
				action = Action.CHECK;
				break;
			default:
				return;
		}

		// also accept these options while a basket is worn (forestry basket used from the equipment tab)
		boolean clickedBasket = w != null && BASKET_IDS.contains(w.getItemId());
		boolean basketWorn = equipment.stream().anyMatch(BASKET_IDS::contains);
		if (clickedBasket || basketWorn)
		{
			markAction(action);
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		// each firemaking XP drop while chopping with an infernal axe is one log burnt instead of kept
		if (event.getSkill() == Skill.FIREMAKING && hasContainer() && has(CHARGED_INFERNAL_AXE_IDS))
		{
			burnedLogs++;
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		int added = newLogs;
		int burned = burnedLogs;
		newLogs = 0;
		burnedLogs = 0;

		if (hasContainer())
		{
			addIfKnown(added - burned);
		}
	}

	/** @return log count from the 'Check' dialogue, or -1 if it can't be read */
	static int parseCheck(String text)
	{
		if (text == null)
		{
			return -1;
		}
		if (text.contains("basket is empty"))
		{
			return 0;
		}
		if (!text.contains("The basket contains"))
		{
			return -1;
		}

		String plain = text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
		Matcher m = CHECK_ENTRY_PATTERN.matcher(plain);
		int total = 0;
		boolean matched = false;
		while (m.find())
		{
			if (LOG_NAMES.contains(m.group(2).trim().toLowerCase()))
			{
				total += Integer.parseInt(m.group(1));
				matched = true;
			}
		}
		return matched ? total : -1;
	}
}
