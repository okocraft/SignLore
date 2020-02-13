package net.okocraft.signlore;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;

import net.milkbowl.vault.economy.Economy;

public class SignLore extends JavaPlugin implements CommandExecutor, TabCompleter {

	private FileConfiguration config;
	private FileConfiguration defaultConfig;

	private NamespacedKey signLoreLineTag = new NamespacedKey(this, "signloreline");
	private NamespacedKey signersTag = new NamespacedKey(this, "signplayers");
	private NamespacedKey isLockedTag = new NamespacedKey(this, "signlock");

	private Economy economy;

	@Override
	public void onEnable() {
		PluginCommand command = Objects.requireNonNull(getCommand("signiture"));
		command.setExecutor(this);
		command.setTabCompleter(this);

		saveDefaultConfig();

		config = getConfig();
		defaultConfig = getDefaultConfig();

		if (!setupEconomy()) {
			throw new ExceptionInInitializerError("Cannot load economy.");
		}
	}

	@Override
	public void onDisable() {
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player)) {
			sender.sendMessage(getMessage("player-only"));
			return false;
		}

		Player player = (Player) sender;

		ItemStack mainHandItem = player.getInventory().getItemInMainHand();
		if (mainHandItem.getType() == Material.AIR) {
			sendMessage(player, "air-cannot-have-lore");
			return true;
		}

		List<String> signers = getSigners(mainHandItem);
		boolean isLocked = isLocked(mainHandItem);

		if (args.length >= 1) {
			if (args[0].equalsIgnoreCase("lock")) {
				if (signers.isEmpty() || !signers.contains(player.getName())) {
					sendMessage(player, "need-sign-to-lock");
					return true;
				}

				if (isLocked) {
					sendMessage(player, "already-locked");
					return true;
				}

				lock(mainHandItem, player);
				sendMessage(player, "locked-item");
				return true;
			} else if (args[0].equalsIgnoreCase("unlock")) {
				if (!isLocked) {
					sendMessage(player, "not-locked");
					return true;
				}

				if (!signers.contains(player.getName())) {
					sendMessage(player, "need-your-sign-to-lock");
					return true;
				}

				unlock(mainHandItem);
				sendMessage(player, "unlocked-item");
				return true;
			} else {
				sendMessage(player, "invalid-argument");
				return true;
			}
		}

		if (isLocked) {
			sendMessage(player, "item-is-locked");
			return true;
		}

		if (signers.contains(player.getName())) {
			sendMessage(player, "cannot-sign-twice");
			return true;
		}

		double price = mainHandItem.getAmount() * config.getDouble("price-per-one", 100);
		if (price < 0 || economy.getBalance(player) < price) {
			sendMessage(player, "not-enough-money");
			return true;
		}

		sign(mainHandItem, player);

		economy.withdrawPlayer(player, price);
		sender.sendMessage(getMessage("success").replaceAll("%price%", String.valueOf(price)));
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) {
			return StringUtil.copyPartialMatches(args[0], List.of("lock", "unlock"), new ArrayList<>());
		}

		return List.of();
	}

	private FileConfiguration getDefaultConfig() {
		InputStream is = getResource("config.yml");
		return YamlConfiguration.loadConfiguration(new InputStreamReader(is));
	}

	private String getMessage(String key) {
		String fullKey = "messages." + key;
		return ChatColor.translateAlternateColorCodes('&', config.getString(fullKey, defaultConfig.getString(fullKey, fullKey)));
	}

	private void sendMessage(CommandSender sender, String key) {
		sender.sendMessage(getMessage(key));
	}

	/**
	 * economyをセットする。
	 * 
	 * @return 成功したらtrue 失敗したらfalse
	 */
	private boolean setupEconomy() {
		if (getServer().getPluginManager().getPlugin("Vault") == null) {
			getLogger().severe("Vault was not found.");
			return false;
		}
		RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
		if (rsp == null) {
			return false;
		}
		economy = rsp.getProvider();
		return true;
	}

	private String createSigniture(Player player) {
		return ChatColor
				.translateAlternateColorCodes('&', config.getString("format", defaultConfig.getString("format")))
				.replaceAll("%player_name%", player.getName());

	}

	private List<String> getSigners(ItemStack item) {
		ItemMeta meta = item.getItemMeta();
		String rawSignPlayers = meta.getPersistentDataContainer().get(signersTag, PersistentDataType.STRING);
		if (rawSignPlayers == null) {
			return new ArrayList<>();
		} else {
			return new ArrayList<>(Arrays.asList(rawSignPlayers.split(",")));
		}
	}

	private boolean sign(ItemStack item, Player signer) {
		List<String> signers = getSigners(item);

		if (signers.contains(signer.getName())) {
			return false;
		}

		ItemMeta meta = item.getItemMeta();
		List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
		Integer signLoreLine = meta.getPersistentDataContainer().get(signLoreLineTag, PersistentDataType.INTEGER);
		if (signLoreLine != null) {
			signLoreLine += 1;
			lore.add(signLoreLine, createSigniture(signer));
		} else {
			lore.add("§r");
			signLoreLine = lore.size();
			lore.add(createSigniture(signer));
			lore.add("§r");
		}

		meta.getPersistentDataContainer().set(signLoreLineTag, PersistentDataType.INTEGER, signLoreLine);
		signers.add(signer.getName());
		StringBuilder sb = new StringBuilder();
		for (String playerName : signers) {
			sb.append(",").append(playerName);
		}
		sb.deleteCharAt(0);
		meta.getPersistentDataContainer().set(signersTag, PersistentDataType.STRING, sb.toString());

		meta.setLore(lore);
		item.setItemMeta(meta);
		return true;
	}

	private void lock(ItemStack item, Player locker) {
		ItemMeta meta = item.getItemMeta();
		meta.getPersistentDataContainer().set(isLockedTag, PersistentDataType.BYTE, (byte) 1);
		List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
		if (lore.size() > 0 && lore.get(0).isEmpty()) {
			lore.remove(lore.size() - 1);
		}
		lore.add("§7§o§m========");
		meta.setLore(lore);
		item.setItemMeta(meta);

	}
	
	private void unlock(ItemStack item) {
		ItemMeta meta = item.getItemMeta();
		meta.getPersistentDataContainer().remove(isLockedTag);
		List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
		lore.remove(lore.size() - 1);
		lore.add("§r");
		meta.setLore(lore);
		item.setItemMeta(meta);
	}

	private boolean isLocked(ItemStack item) {
		return item.getItemMeta().getPersistentDataContainer().getOrDefault(isLockedTag, PersistentDataType.BYTE,
				(byte) 0) == (byte) 1;
	}
}
