/*
 * Copyright (c) 2021, molo-pl <https://github.com/molo-pl>
 * All rights reserved.
 *
 * Ported from the Fish Barrel plugin (https://github.com/molo-pl/runelite-plugins) into AFK Mini Mode,
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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import java.util.Map;
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

public class FishBarrelTracker extends StorageTracker
{
	private static final int GAME_TICK_MARGIN = 3;

	private static final Pattern FISH_CAUGHT_MESSAGE = Pattern.compile(
		"^You catch (an?|some)(?: raw)? ([a-zA-Z ]+)[.!]?( It hardens as you handle it with your ice gloves\\.)?$");

	private static final String RADA_DOUBLE_CATCH_MESSAGE = "Rada's blessing enabled you to catch an extra fish.";
	private static final String FLAKES_DOUBLE_CATCH_MESSAGE = "The spirit flakes enabled you to catch an extra fish.";
	private static final String CORMORANT_CATCH_MESSAGE = "Your cormorant returns with its catch.";

	private static final String BANK_FULL_MESSAGE = "Your bank could not hold your fish.";
	private static final String BARREL_FULL_MESSAGE = "The barrel is full. It may be emptied at a bank.";
	private static final String BANK_EMPTY_MESSAGE = "You empty all of your containers into the bank.";
	private static final String CONTAINERS_EMPTY_MESSAGE = "Your containers are already empty.";

	private static final String CHECK_EMPTY_MESSAGE = "The barrel is empty.";
	private static final String CHECK_ENTRY_REGEX = "([0-9]+) x [a-zA-Z ]+,? ?";
	private static final Pattern CHECK_ENTRY_PATTERN = Pattern.compile(CHECK_ENTRY_REGEX);
	private static final Pattern CHECK_FULL_PATTERN = Pattern.compile("^The barrel contains: (" + CHECK_ENTRY_REGEX + ")+$");

	private static final ImmutableList<Integer> BARREL_IDS = ImmutableList.of(
		ItemID.FISH_BARREL_CLOSED,
		ItemID.FISH_BARREL_OPEN,
		ItemID.FISH_SACK_BARREL_CLOSED,
		ItemID.FISH_SACK_BARREL_OPEN
	);

	private static final ImmutableList<Integer> OPEN_BARREL_IDS = ImmutableList.of(
		ItemID.FISH_BARREL_OPEN,
		ItemID.FISH_SACK_BARREL_OPEN
	);

	// name as it appears in chat -> item ID
	private static final Map<String, Integer> FISH_TYPES_BY_NAME = ImmutableMap.<String, Integer>builder()
		// singular 'shrimp' may occur when fishing for Karambwanji
		.put("shrimp", ItemID.RAW_SHRIMP)
		.put("shrimps", ItemID.RAW_SHRIMP)
		.put("sardine", ItemID.RAW_SARDINE)
		.put("herring", ItemID.RAW_HERRING)
		.put("anchovies", ItemID.RAW_ANCHOVIES)
		.put("mackerel", ItemID.RAW_MACKEREL)
		.put("trout", ItemID.RAW_TROUT)
		.put("cod", ItemID.RAW_COD)
		.put("pike", ItemID.RAW_PIKE)
		.put("slimy swamp eel", ItemID.MORT_SLIMEY_EEL)
		.put("salmon", ItemID.RAW_SALMON)
		.put("tuna", ItemID.RAW_TUNA)
		.put("rainbow fish", ItemID.HUNTING_RAW_FISH_SPECIAL)
		.put("cave eel", ItemID.RAW_CAVE_EEL)
		.put("lobster", ItemID.RAW_LOBSTER)
		.put("bass", ItemID.RAW_BASS)
		.put("leaping trout", ItemID.BRUT_SPAWNING_TROUT)
		.put("swordfish", ItemID.RAW_SWORDFISH)
		.put("lava eel", ItemID.RAW_LAVA_EEL)
		.put("leaping salmon", ItemID.BRUT_SPAWNING_SALMON)
		.put("monkfish", ItemID.RAW_MONKFISH)
		.put("Karambwan", ItemID.TBWT_RAW_KARAMBWAN)
		.put("leaping sturgeon", ItemID.BRUT_STURGEON)
		.put("shark", ItemID.RAW_SHARK)
		.put("infernal eel", ItemID.INFERNAL_EEL)
		.put("anglerfish", ItemID.RAW_ANGLERFISH)
		.put("dark crab", ItemID.RAW_DARK_CRAB)
		.put("sacred eel", ItemID.SNAKEBOSS_EEL)
		.put("swordtip squid", ItemID.RAW_SWORDTIP_SQUID)
		.put("jumbo squid", ItemID.RAW_JUMBO_SQUID)
		.build();

	private static final Set<Integer> ALL_FISH_TYPES = ImmutableSet.<Integer>builder()
		.addAll(FISH_TYPES_BY_NAME.values())
		// cormorant catches on Molch island
		.add(ItemID.AERIAL_FISHING_BLUEGILL)
		.add(ItemID.AERIAL_FISHING_COMMON_TENCH)
		.add(ItemID.AERIAL_FISHING_MOTTLED_EEL)
		.add(ItemID.AERIAL_FISHING_GREATER_SIREN)
		// not caught directly, but can be put in the barrel
		.add(ItemID.RAW_SEATURTLE)
		.add(ItemID.RAW_MANTARAY)
		.build();

	// per-tick counters, reset every GameTick
	private int fishCaughtMessages;
	private int newFishInInventory;
	private int cookingXpDrops; // infernal harpoon cooks some catches

	@Inject
	FishBarrelTracker(Client client, ClientThread clientThread)
	{
		super(client, clientThread, "barrel", GAME_TICK_MARGIN, BARREL_IDS, OPEN_BARREL_IDS, FishBarrelTracker::parseCheck);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() == ChatMessageType.GAMEMESSAGE && hasContainer())
		{
			switch (event.getMessage())
			{
				case BANK_FULL_MESSAGE:
					// couldn't deposit everything: lost track
					forget();
					break;
				case BARREL_FULL_MESSAGE:
					setExact(CAPACITY);
					break;
				case BANK_EMPTY_MESSAGE:
				case CONTAINERS_EMPTY_MESSAGE:
					setExact(0);
					break;
			}
		}
		else if (event.getType() == ChatMessageType.SPAM && isCollecting())
		{
			Matcher m = FISH_CAUGHT_MESSAGE.matcher(event.getMessage());
			if (m.matches())
			{
				if (FISH_TYPES_BY_NAME.containsKey(m.group(2)))
				{
					fishCaughtMessages++;
				}
			}
			else
			{
				switch (event.getMessage())
				{
					case RADA_DOUBLE_CATCH_MESSAGE:
					case FLAKES_DOUBLE_CATCH_MESSAGE:
					case CORMORANT_CATCH_MESSAGE:
						fishCaughtMessages++;
						break;
				}
			}
		}
	}

	@Override
	protected void inventoryChanged(Multiset<Integer> prev)
	{
		int addedFish = Multisets.filter(Multisets.difference(inventory, prev), ALL_FISH_TYPES::contains).size();
		int removedFish = Multisets.filter(Multisets.difference(prev, inventory), ALL_FISH_TYPES::contains).size();

		// fish landing in the inventory while fishing means the barrel is full
		newFishInInventory += addedFish;

		if (removedFish > 0 && consumeAction(Action.FILL))
		{
			if (inventory.stream().anyMatch(ALL_FISH_TYPES::contains))
			{
				// fish left over after 'Fill': barrel is full
				setExact(CAPACITY);
			}
			else
			{
				addIfKnown(removedFish);
			}
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() == Skill.COOKING && event.getXp() > 0)
		{
			cookingXpDrops++;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (fishCaughtMessages > 0)
		{
			if (newFishInInventory == 0)
			{
				// everything went into the barrel
				addIfKnown(Math.max(0, fishCaughtMessages - cookingXpDrops));
			}
			else
			{
				setExact(CAPACITY);
			}
		}
		else if (consumeAction(Action.EMPTY))
		{
			setExact(0);
		}

		fishCaughtMessages = 0;
		newFishInInventory = 0;
		cookingXpDrops = 0;
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		switch (event.getMenuAction())
		{
			case CC_OP:
			case CC_OP_LOW_PRIORITY:
				break;
			default:
				return;
		}

		Widget w = event.getWidget();
		if (w == null || !BARREL_IDS.contains(w.getItemId()))
		{
			return;
		}

		switch (event.getMenuOption())
		{
			case "Fill":
				markAction(Action.FILL);
				break;
			case "Empty":
			case "Empty to bank":
				markAction(Action.EMPTY);
				break;
			case "Check":
				markAction(Action.CHECK);
				break;
		}
	}

	/** @return fish count from the 'Check' dialogue, or -1 if it can't be read */
	static int parseCheck(String text)
	{
		if (text == null || text.trim().isEmpty())
		{
			return -1;
		}
		text = text.replace("<br>", " ").trim();

		if (CHECK_EMPTY_MESSAGE.equals(text))
		{
			return 0;
		}
		if (!CHECK_FULL_PATTERN.matcher(text).matches())
		{
			return -1;
		}

		int count = 0;
		Matcher m = CHECK_ENTRY_PATTERN.matcher(text);
		while (m.find())
		{
			count += Integer.parseInt(m.group(1));
		}
		return count;
	}
}
