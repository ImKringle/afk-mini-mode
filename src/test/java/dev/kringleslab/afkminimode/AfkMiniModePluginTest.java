package dev.kringleslab.afkminimode;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AfkMiniModePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AfkMiniModePlugin.class);
		RuneLite.main(args);
	}
}
