package fr.iban.guilds;

import fr.iban.bukkitcore.CoreBukkitPlugin;
import fr.iban.guilds.command.GuildCMD;
import fr.iban.guilds.listener.ChatListeners;
import fr.iban.guilds.listener.CoreMessageListener;
import fr.iban.guilds.listener.GuildListeners;
import fr.iban.guilds.listener.ServiceListeners;
import fr.iban.guilds.util.GuildPlaceHolders;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import revxrsal.commands.bukkit.BukkitCommandHandler;
import revxrsal.commands.exception.CommandErrorException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GuildsPlugin extends JavaPlugin {

    private static GuildsPlugin instance;
    private GuildsManager guildsManager;
    public static final String GUILD_SYNC_CHANNEL = "GuildSyncChannel";
    public static final String GUILD_PLAYER_SYNC_CHANNEL = "GuildPlayerSyncChannel";
    public static final String GUILD_INVITE_ADD = "AddGuildInviteChannel";
    public static final String GUILD_INVITE_REVOKE = "RevokeGuildInviteSyncChannel";
    public static final String GUILD_ALLIANCE_REQUEST = "GuildAllianceRequestChannel";
    public static final String GUILD_ALLIANCE_ACCEPT = "AddGuildAllianceAcceptChannel";
    public static final String GUILD_ALLIANCE_REVOKE = "AddGuildAllianceRevokeChannel";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Economy econ = null;

    @Override
    public void onEnable() {
        instance = this;
        this.guildsManager = new GuildsManager(this);
        getServer().getServicesManager().register(GuildsManager.class, guildsManager, this, ServicePriority.Normal);
        registerCommands();
        setupEconomy();
        registerListeners(
                new CoreMessageListener(this),
                new GuildListeners(this),
                new ServiceListeners(this),
                new ChatListeners(this)
        );

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new GuildPlaceHolders(this).register();
        }
    }

    @Override
    public void onDisable() {
        executor.shutdown();
    }

    public static GuildsPlugin getInstance() {
        return instance;
    }

    private void registerListeners(Listener... listeners) {
        PluginManager pm = Bukkit.getPluginManager();
        for (Listener listener : listeners) {
            pm.registerEvents(listener, this);
        }
    }

    private void registerCommands() {
        BukkitCommandHandler commandHandler = BukkitCommandHandler.create(this);
        commandHandler.accept(CoreBukkitPlugin.getInstance().getCommandHandlerVisitor());

        //Guild resolver
        commandHandler.getAutoCompleter().registerParameterSuggestions(Guild.class, (args, sender, command) -> guildsManager.getGuildNames());
        commandHandler.registerValueResolver(Guild.class, context -> {
            String value = context.arguments().pop();
            Guild guild = guildsManager.getGuildByName(value);
            if (guild == null) {
                throw new CommandErrorException("La guilde " + value + " n''existe pas.");
            }
            return guild;
        });

        commandHandler.register(new GuildCMD(this));
        commandHandler.registerBrigadier();
    }

    public void runAsyncQueued(Runnable runnable) {
        executor.execute(runnable);
    }

    public void setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return;
        }
        econ = rsp.getProvider();
    }

    public Economy getEconomy() {
        return econ;
    }

    public GuildsManager getGuildsManager() {
        return guildsManager;
    }
}
