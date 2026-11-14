package dev.kringleslab.afkminimode;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class CheckParserTest
{
	@Test
	public void fishBarrel()
	{
		assertEquals(-1, FishBarrelTracker.parseCheck("Hello, World!"));
		assertEquals(-1, FishBarrelTracker.parseCheck("28 x Raw swordfish"));
		assertEquals(0, FishBarrelTracker.parseCheck("The barrel is empty."));
		assertEquals(27, FishBarrelTracker.parseCheck("The barrel contains:<br>27 x Raw anglerfish"));
		// examples from the original Fish Barrel plugin's tests
		assertEquals(12, FishBarrelTracker.parseCheck(String.join("<br>",
			"The barrel contains:",
			"1 x Raw anglerfish, 2 x Raw monkfish, 3 x Raw",
			"shrimps, 1 x Raw anchovies, 1 x Raw salmon, 1 x Raw",
			"cod, 1 x Raw macerel, 1 x Raw tuna, 1 x Raw bass")));
	}

	@Test
	public void logBasket()
	{
		assertEquals(-1, LogBasketTracker.parseCheck("Hello, World!"));
		assertEquals(0, LogBasketTracker.parseCheck("The basket is empty."));
		assertEquals(8, LogBasketTracker.parseCheck("The basket contains:<br>5 x Oak logs, 3 x Willow logs"));
		assertEquals(28, LogBasketTracker.parseCheck("The basket contains:<br>28 x Logs"));
	}

	@Test
	public void coalBag()
	{
		assertEquals(-1, CoalBagTracker.parseCheck("You manage to mine some coal."));
		assertEquals(0, CoalBagTracker.parseCheck("The coal bag is empty."));
		assertEquals(0, CoalBagTracker.parseCheck("The coal bag is now empty."));
		assertEquals(1, CoalBagTracker.parseCheck("The coal bag contains one piece of coal."));
		assertEquals(27, CoalBagTracker.parseCheck("The coal bag contains 27 pieces of coal."));
		assertEquals(9, CoalBagTracker.parseCheck("The coal bag still<br>contains 9 pieces of coal."));
	}

	@Test
	public void gemBag()
	{
		assertNull(GemBagTracker.parseCheck("You just found a ruby!"));
		assertArrayEquals(new int[]{-1, -1, -1, 12, 3, 0, 1, 60},
			GemBagTracker.parseCheck("Sapphires: 12 / Emeralds: 3 / Rubies: 0 / Diamonds: 1 / Dragonstones: 60"));
		assertArrayEquals(new int[]{2, 0, 5, -1, -1, -1, -1, -1},
			GemBagTracker.parseCheck("Left in bag: Opal: 2 / Jade: 0 / Red Topaz: 5"));
		assertEquals(5, GemBagTracker.gemIndex("ruby"));
		assertEquals(2, GemBagTracker.gemIndex("Uncut Red Topaz"));
		assertEquals(7, GemBagTracker.gemIndex("Dragonstone"));
		assertEquals(-1, GemBagTracker.gemIndex("coal"));
	}
}
