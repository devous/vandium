package dev.devous.vandium.command;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.help.GenericCommandHelpTopic;
import org.bukkit.help.HelpTopic;
import org.bukkit.help.HelpTopicComparator;
import org.bukkit.help.IndexHelpTopic;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.SimplePluginManager;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.Map.Entry;

public class CommandFramework implements CommandExecutor {

	private final Map<String, Entry<Method, Object>> commandMap = new HashMap<>();
	private CommandMap map;
	private final Plugin plugin;

	/**
	 * Initializes the command framework and sets up the command maps
	 */
	public CommandFramework(Plugin plugin) {
		this.plugin = plugin;
		if (plugin.getServer().getPluginManager() instanceof SimplePluginManager) {
			SimplePluginManager manager = (SimplePluginManager) plugin.getServer().getPluginManager();

			try {
				Field field = SimplePluginManager.class.getDeclaredField("commandMap");
				field.setAccessible(true);
				map = (CommandMap) field.get(manager);
			} catch (IllegalArgumentException | SecurityException | IllegalAccessException | NoSuchFieldException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public boolean onCommand(CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
		return handleCommand(sender, cmd, label, args);
	}

	/**
	 * Handles commands. Used in the onCommand method in your JavaPlugin class
	 *
	 * @param sender The {@link CommandSender} parsed from
	 *            onCommand
	 * @param cmd The {@link org.bukkit.command.Command} parsed from onCommand
	 * @param label The label parsed from onCommand
	 * @param args The arguments parsed from onCommand
	 * @return Always returns true for simplicity's sake in onCommand
	 */
	public boolean handleCommand(CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
		for (int i = args.length; i >= 0; i--) {
			StringBuilder builder = new StringBuilder();
			builder.append(label.toLowerCase());

			for (int x = 0; x < i; x++)
				builder.append(".").append(args[x].toLowerCase());

			String cmdLabel = builder.toString();

			if (commandMap.containsKey(cmdLabel)) {
				Method method = commandMap.get(cmdLabel).getKey();
				Object methodObject = commandMap.get(cmdLabel).getValue();
				Command command = method.getAnnotation(Command.class);

				if (!command.permission().equals("") && !sender.hasPermission(command.permission())) {
					sender.sendMessage(ChatColor.translateAlternateColorCodes('&', command.noPerm()));
					return true;
				}

				if (command.inGameOnly() && !(sender instanceof Player)) {
					sender.sendMessage("This command is only performable in game");
					return true;
				}

				try {
					method.invoke(methodObject, new CommandArgs(sender, cmd, label, args,
							cmdLabel.split("\\.").length - 1));
				} catch (IllegalArgumentException | IllegalAccessException | InvocationTargetException e) {
					e.printStackTrace();
				}
				return true;
			}
		}

		defaultCommand(new CommandArgs(sender, cmd, label, args, 0));
		return true;
	}

	/**
	 * Registers all command and completer methods inside of the object. Similar
	 * to Bukkit's registerEvents method.
	 *
	 * @param cmd The object to register the commands of
	 */
	public void registerCommands(ICommand cmd) {
		for (Method method : cmd.getClass().getMethods()) {
			Command command = method.getAnnotation(Command.class);
			if (command != null) {
				if (method.getParameterTypes().length > 1 || method.getParameterTypes()[0] != CommandArgs.class) {
					System.out.println("Unable to register command " + method.getName() + ". Unexpected method arguments");
					continue;
				}

				registerCommand(command, command.name(), method, cmd);

				for (String alias : command.aliases())
					registerCommand(command, alias, method, cmd);

			}

			Completer completer = method.getAnnotation(Completer.class);
			if (completer != null) {
				if (method.getParameterTypes().length != 1 || method.getParameterTypes()[0] != CommandArgs.class) {
					System.out.println("Unable to register tab completer " + method.getName() + ". Unexpected method arguments");
					continue;
				}

				if (method.getReturnType() != List.class) {
					System.out.println("Unable to register tab completer " + method.getName() + ". Unexpected return type");
					continue;
				}

				registerCompleter(completer.name(), method, cmd);

				for (String alias : completer.aliases())
					registerCompleter(alias, method, cmd);
			}
		}
	}

	public void registerHelp() {
		Set<HelpTopic> help = new TreeSet<>(HelpTopicComparator.helpTopicComparatorInstance());
		for (String s : commandMap.keySet()) {
			if (!s.contains(".")) {
				org.bukkit.command.Command cmd = map.getCommand(s);
				HelpTopic topic = new GenericCommandHelpTopic(cmd);
				help.add(topic);
			}
		}
		IndexHelpTopic topic = new IndexHelpTopic(plugin.getName(), "All commands for " + plugin.getName(), null, help,
				"Below is a list of all " + plugin.getName() + " commands:");
		Bukkit.getServer().getHelpMap().addTopic(topic);
	}

	public void registerCommand(Command command, String label, Method m, Object obj) {
		commandMap.put(label.toLowerCase(), new AbstractMap.SimpleEntry<>(m, obj));
		commandMap.put(plugin.getName() + ':' + label.toLowerCase(), new AbstractMap.SimpleEntry<>(m, obj));

		String cmdLabel = label.split("\\.")[0].toLowerCase();

		if (map.getCommand(cmdLabel) == null) {
			org.bukkit.command.Command cmd = new BukkitCommand(cmdLabel, this, plugin);
			map.register(plugin.getName(), cmd);
		}

		if (!command.description().equalsIgnoreCase("") && cmdLabel.equals(label))
			map.getCommand(cmdLabel).setDescription(command.description());

		if (!command.usage().equalsIgnoreCase("") && cmdLabel.equals(label))
			map.getCommand(cmdLabel).setUsage(command.usage());

	}

	public void registerCompleter(String label, Method m, Object obj) {
		String cmdLabel = label.split("\\.")[0].toLowerCase();

		if (map.getCommand(cmdLabel) == null) {
			org.bukkit.command.Command command = new BukkitCommand(cmdLabel, this, plugin);
			map.register(plugin.getName(), command);
		}

		if (map.getCommand(cmdLabel) instanceof BukkitCommand) {
			BukkitCommand command = (BukkitCommand) map.getCommand(cmdLabel);

			if (command.completer == null)
				command.completer = new BukkitCompleter();


			command.completer.addCompleter(label, m, obj);

		} else if (map.getCommand(cmdLabel) instanceof PluginCommand) {
			try {
				Object command = map.getCommand(cmdLabel);
				Field field = command.getClass().getDeclaredField("completer");
				field.setAccessible(true);

				if (field.get(command) == null) {
					BukkitCompleter completer = new BukkitCompleter();
					completer.addCompleter(label, m, obj);
					field.set(command, completer);

				} else if (field.get(command) instanceof BukkitCompleter) {
					BukkitCompleter completer = (BukkitCompleter) field.get(command);
					completer.addCompleter(label, m, obj);
				} else {
					System.out.println("Unable to register tab completer " + m.getName() + ". A tab completer is already registered for that command!");
				}
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
	}

	private void defaultCommand(CommandArgs args) {
		args.getSender().sendMessage(ChatColor.RED + args.getLabel() + " isn't handled!");
	}

}
