/*
 * Copyright (c) 2021, Nick Wolff
 * Copyright (c) 2022, TicTac7x
 * All rights reserved.
 *
 * Coal bag tracking for AFK Mini Mode, following the game messages and handling of the Coal Bag plugin
 * (https://github.com/WolffTech/coal-bag-plugin) and the Charges plugin, including Superheat Item coal use
 * (https://github.com/TicTac7x/runelite-plugins/tree/plugin-charges).
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
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Coal bag. Every check, fill and empty reports the exact count in chat (or sometimes in a dialogue box), and an
 * open bag catches coal as it's mined. Message wording from the Coal Bag (WolffTech) and tictac7x charges plugins.
 */
public class CoalBagTracker extends StorageTracker
{
	private static final int GAME_TICK_MARGIN = 2;
	private static final int CAPACITY = 27;
	private static final int CAPE_CAPACITY = 36;

	private static final Pattern EMPTY_PATTERN = Pattern.compile("The coal bag is (?:now )?empty\\.");
	private static final Pattern CONTAINS_PATTERN = Pattern.compile("The coal bag (?:still )?contains (\\d+|one) pieces? of coal\\.");
	private static final String MINED_PREFIX = "You manage to mine some ";
	private static final String COAL_MINED_MESSAGE = "You manage to mine some coal.";
	private static final String CELESTIAL_RING_MESSAGE = "Your Celestial ring allows you to mine an additional ore.";
	private static final String VARROCK_PLATEBODY_MESSAGE = "The Varrock platebody enabled you to mine an additional ore.";

	private static final ImmutableList<Integer> BAG_IDS = ImmutableList.of(ItemID.COAL_BAG, ItemID.COAL_BAG_OPEN);
	private static final ImmutableList<Integer> OPEN_BAG_IDS = ImmutableList.of(ItemID.COAL_BAG_OPEN);
	private static final ImmutableList<Integer> CAPE_IDS = ImmutableList.of(
		ItemID.SKILLCAPE_SMITHING, ItemID.SKILLCAPE_SMITHING_TRIMMED, ItemID.SKILLCAPE_MAX, ItemID.SKILLCAPE_MAX_WORN);

	// coal a Superheat Item cast on each ore uses, inventory coal first; same as tictac7x charges
	private static final Map<String, Integer> SUPERHEAT_COAL = ImmutableMap.of(
		"Superheat Item -> Iron ore", 2,
		"Superheat Item -> Lovakite ore", 2,
		"Superheat Item -> Mithril ore", 4,
		"Superheat Item -> Adamantite ore", 6,
		"Superheat Item -> Runite ore", 8);

	// the bonus-ore messages don't say which ore, so they only count right after a coal message
	private boolean lastMinedCoal;
	// coal the last-clicked Superheat needs
	private int superheatCoal;
	private int lastSmithingXp = -1;

	@Inject
	CoalBagTracker(Client client, ClientThread clientThread)
	{
		super(client, clientThread, "coal bag", GAME_TICK_MARGIN, BAG_IDS, OPEN_BAG_IDS, CoalBagTracker::parseCheck);
	}

	@Override
	public int getCapacity()
	{
		return equipment.stream().anyMatch(CAPE_IDS::contains) ? CAPE_CAPACITY : CAPACITY;
	}

	@Override
	protected void reset()
	{
		super.reset();
		lastSmithingXp = -1;
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() != Skill.SMITHING)
		{
			return;
		}
		boolean xpDrop = lastSmithingXp != -1 && event.getXp() > lastSmithingXp;
		lastSmithingXp = event.getXp();
		// a Superheat clicked in the last couple of ticks earned smithing XP: the coal it took beyond the inventory's came from the bag
		if (xpDrop && hasContainer() && consumeAction(Action.SUPERHEAT))
		{
			addIfKnown(-Math.max(0, superheatCoal - inventory.count(ItemID.COAL)));
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if ((event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM) || !hasContainer())
		{
			return;
		}
		String message = event.getMessage();
		// check, fill and empty all state the exact count
		int count = parseCheck(message);
		if (count != -1)
		{
			setExact(count);
		}
		else if (message.equals(EMPTIED_CONTAINERS_TO_BANK_MESSAGE) || message.equals(CONTAINERS_ALREADY_EMPTY_MESSAGE))
		{
			setExact(0);
		}
		else if (message.startsWith(MINED_PREFIX))
		{
			// mined coal goes straight into an open bag (or the inventory once the bag is full; addIfKnown caps it)
			lastMinedCoal = message.equals(COAL_MINED_MESSAGE);
			if (lastMinedCoal && isCollecting())
			{
				addIfKnown(1);
			}
		}
		else if (lastMinedCoal && isCollecting()
			&& (message.equals(CELESTIAL_RING_MESSAGE) || message.equals(VARROCK_PLATEBODY_MESSAGE)))
		{
			addIfKnown(1);
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// remember a Superheat click; onStatChanged settles it once the smithing XP confirms the cast worked
		Integer coal = "Cast".equals(event.getMenuOption()) ? SUPERHEAT_COAL.get(Text.removeTags(event.getMenuTarget())) : null;
		if (coal != null)
		{
			superheatCoal = coal;
			markAction(Action.SUPERHEAT);
		}

		Widget w = event.getWidget();
		if (w == null || !BAG_IDS.contains(w.getItemId()))
		{
			return;
		}
		switch (event.getMenuOption().toLowerCase())
		{
			case "check":
			case "fill":
			case "empty":
				// the count sometimes comes in a dialogue box instead of chat
				markAction(Action.CHECK);
				break;
		}
	}

	/** @return coal count from a check/fill/empty message, or -1 if it isn't one */
	static int parseCheck(String text)
	{
		if (text == null)
		{
			return -1;
		}
		// the dialogue box version wraps lines with <br> and may carry colour tags; flatten it to match the chat wording
		String plain = text.replace("<br>", " ").replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
		if (EMPTY_PATTERN.matcher(plain).find())
		{
			return 0;
		}
		Matcher m = CONTAINS_PATTERN.matcher(plain);
		if (!m.find())
		{
			return -1;
		}
		return m.group(1).equals("one") ? 1 : Integer.parseInt(m.group(1));
	}
}
