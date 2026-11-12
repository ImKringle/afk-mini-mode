package dev.kringleslab.afkminimode;

import static org.junit.Assert.assertEquals;
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
}
