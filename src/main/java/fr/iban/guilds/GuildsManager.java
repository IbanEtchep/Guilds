package fr.iban.guilds;

import fr.iban.bukkitcore.CoreBukkitPlugin;
import fr.iban.bukkitcore.utils.SLocationUtils;
import fr.iban.guilds.enums.ChatMode;
import fr.iban.guilds.enums.Rank;
import fr.iban.guilds.event.GuildCreateEvent;
import fr.iban.guilds.event.GuildDisbandEvent;
import fr.iban.guilds.event.GuildPostDisbandEvent;
import fr.iban.guilds.storage.SqlStorage;
import fr.iban.guilds.util.GuildRequestMessage;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class GuildsManager {

    private final GuildsPlugin plugin;
    private final SqlStorage storage;

    private final Map<UUID, Guild> guilds = new HashMap<>();

    public GuildsManager(GuildsPlugin plugin) {
        this.plugin = plugin;
        this.storage = new SqlStorage();
        load();
    }

    @Nullable
    public Guild getGuildByPlayerId(UUID uuid) {
        return guilds.values().stream().filter(guild -> guild.getMember(uuid) != null).findFirst().orElse(null);
    }

    @Nullable
    public Guild getGuildByPlayer(Player player) {
        return getGuildByPlayerId(player.getUniqueId());
    }

    @Nullable
    public Guild getGuildByName(String name) {
        return guilds.values().stream().filter(guild -> guild.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    @Nullable
    public GuildPlayer getGuildPlayer(UUID uuid) {
        Guild guild = getGuildByPlayerId(uuid);
        return guild == null ? null : guild.getMember(uuid);
    }

    public List<String> getGuildNames() {
        return guilds.values().stream().map(Guild::getName).toList();
    }

    public void createGuild(Player player, String name) {
        if (guilds.values().stream().anyMatch(g -> g.getName().equalsIgnoreCase(name))) {
            player.sendMessage("§cUne guilde existe déjà au nom de " + name + ".");
            return;
        }

        if (getGuildPlayer(player.getUniqueId()) != null) {
            player.sendMessage("§cVous êtes déjà dans une guilde !");
            return;
        }

        Guild guild = new Guild(name);
        GuildPlayer guildPlayer = new GuildPlayer(player.getUniqueId(), guild.getId(), Rank.OWNER, ChatMode.PUBLIC);
        guild.getMembers().put(player.getUniqueId(), guildPlayer);
        storage.saveGuild(guild);
        storage.saveGuildPlayer(guildPlayer);
        guilds.put(guild.getId(), guild);
        new GuildCreateEvent(guild).callEvent();
        saveGuildToDB(guild);
        player.sendMessage("§aVous avez crée une guilde au nom de " + name + ".");
    }

    public void toggleChatMode(Player player) {
        GuildPlayer guildPlayer = getGuildPlayer(player.getUniqueId());

        if (guildPlayer == null) {
            player.sendMessage("§cVous n'avez pas de guilde !");
            return;
        }

        if (guildPlayer.getChatMode() == ChatMode.PUBLIC) {
            guildPlayer.setChatMode(ChatMode.GUILD);
        } else {
            guildPlayer.setChatMode(ChatMode.PUBLIC);
        }

        player.sendMessage("§fVotre chat est désormais en : §b" + guildPlayer.getChatMode().toString());
        saveGuildPlayerToDB(guildPlayer);
    }

    public void disbandGuild(Player player) {
        Guild guild = getGuildByPlayerId(player.getUniqueId());
        if (guild == null) {
            player.sendMessage("§cVous n'avez pas de guilde !");
            return;
        }

        GuildPlayer guildPlayer = guild.getMember(player.getUniqueId());
        if (!guildPlayer.isGranted(Rank.OWNER)) {
            player.sendMessage("§cVous devez être fondateur de la guilde pour la dissoudre.");
            return;
        }

        GuildDisbandEvent disbandEvent = new GuildDisbandEvent(guild);
        disbandEvent.callEvent();

        if (disbandEvent.isCancelled()) return;

        guild.getMembers().forEach((uuid, gp) -> {
            gp.sendMessageIfOnline("§cVotre guilde a été dissoute.");
            deleteGuildPlayerFromDB(uuid);
        });

        guilds.remove(guild.getId());
        deleteGuildFromDB(guildPlayer.getGuildId());
        new GuildPostDisbandEvent(guild).callEvent();
    }

    public void joinGuild(Player player, Guild guild) {
        UUID uuid = player.getUniqueId();
        if (getGuildByPlayerId(uuid) != null) {
            player.sendMessage("§cVous êtes déjà dans une guilde !");
            return;
        }

        if (!guild.getInvites().contains(uuid) || !player.hasPermission("guilds.bypass")) {
            player.sendMessage("§cVous n'avez pas d'invitation à rejoindre cette guilde.");
            return;
        }

        GuildPlayer guildPlayer = new GuildPlayer(uuid, guild.getId(), Rank.MEMBER, ChatMode.PUBLIC);
        guild.sendMessageToOnlineMembers("§7" + player.getName() + " a rejoint la guilde.");
        guild.getMembers().put(uuid, guildPlayer);
        saveGuildPlayerToDB(guildPlayer);
    }

    public void quitGuild(Player player) {
        Guild guild = getGuildByPlayer(player);

        if (guild == null) {
            player.sendMessage("§cVous n'avez pas de guilde !");
            return;
        }

        GuildPlayer guildPlayer = guild.getMember(player.getUniqueId());

        if (guildPlayer.getRank() == Rank.OWNER) {
            player.sendMessage("§cVous ne pouvez pas quitter la guilde en étant fondateur. Veuillez promouvoir quelqu'un fondateur ou dissoudre la guilde.");
            return;
        }

        guild.getMembers().remove(guildPlayer.getUuid());
        deleteGuildPlayerFromDB(guildPlayer.getUuid());
        guild.sendMessageToOnlineMembers("§7" + player.getName() + " a quitté la guilde.");
        player.sendMessage("§cVous avez quitté votre guilde.");
    }

    public void invite(Player player, OfflinePlayer target) {
        Guild guild = getGuildByPlayer(player);

        if (guild == null) {
            player.sendMessage("§cVous n'avez pas de guilde !");
            return;
        }

        if (!guild.getMember(player.getUniqueId()).isGranted(Rank.MODERATOR)) {
            player.sendMessage("§cVous n'avez pas la permission d'inviter des gens dans la guilde.");
            return;
        }

        if (guild.getInvites().contains(target.getUniqueId())) {
            player.sendMessage("§cVous avez déjà envoyé une invitation à ce joueur.");
            return;
        }

        if (guild.getMember(target.getUniqueId()) != null) {
            player.sendMessage("§cCe joueur est déjà dans votre guilde.");
            return;
        }

        guild.getInvites().add(target.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            guild.getInvites().remove(target.getUniqueId());
        }, 2400L);
        CoreBukkitPlugin core = CoreBukkitPlugin.getInstance();
        core.getMessagingManager().sendMessage(GuildsPlugin.GUILD_INVITE_ADD,
                new GuildRequestMessage(guild.getId(), target.getUniqueId()));
        core.getPlayerManager().sendMessageRawIfOnline(target.getUniqueId(), "[\"\",{\"text\":\"Vous avez reçu une invitation à rejoindre la guilde\",\"color\":\"green\"},{\"text\":\" " + guild.getName() + "\",\"color\":\"dark_green\"},{\"text\":\". Tapez \",\"color\":\"green\"},{\"text\":\"/guild join " + guild.getName() + "\",\"bold\":true,\"color\":\"white\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/guild join " + guild.getName() + "\"},\"hoverEvent\":{\"action\":\"show_text\",\"contents\":\"Clic pour accepter\"}},{\"text\":\" ou cliquez\",\"color\":\"green\"},{\"text\":\" a pour accepter.\",\"color\":\"green\"}]");
        player.sendMessage("§aVous avez invité " + target.getName() + " à rejoindre votre guilde.");
    }

    public void revokeInvite(Player player, OfflinePlayer target) {
        Guild guild = getGuildByPlayer(player);

        if (guild == null) {
            player.sendMessage("§cVous n'avez pas de guilde !");
            return;
        }

        if (!guild.getMember(player.getUniqueId()).isGranted(Rank.MODERATOR)) {
            player.sendMessage("§cVous n'avez pas la permission de révoquer une invitation.");
            return;
        }

        if (!guild.getInvites().contains(target.getUniqueId())) {
            player.sendMessage("§cCe joueur n'a pas d'invitation.");
            return;
        }

        guild.getInvites().remove(target.getUniqueId());
        player.sendMessage("§cVous avez révoqué l'invitation envoyée à " + target.getName() + ".");
        CoreBukkitPlugin core = CoreBukkitPlugin.getInstance();
        core.getPlayerManager().sendMessageIfOnline(target.getUniqueId(),
                "§cL'invitation que vous avez reçu de §2§l" + guild.getName() + "§a a expiré.");
    }

    public void guildDeposit(Player player, double amount) {
        Guild guild = getGuildByPlayer(player);
        Economy economy = plugin.getEconomy();

        if (guild == null) {
            player.sendMessage("§cVous n'avez pas de guilde !");
            return;
        }

        if (economy == null) {
            player.sendMessage("§cLa banque n'est pas accessible.");
            return;
        }

        double playerBalance = economy.getBalance(player);
        if (playerBalance < amount) {
            player.sendMessage("§cVous n'avez pas " + economy.format(amount) + "§c.");
            return;
        }

        economy.withdrawPlayer(player, amount);
        guild.setBalance(guild.getBalance() + amount);
        player.sendMessage("§aVous avez déposé " + economy.format(amount) + " §adans la banque de votre guilde. " +
                "Nouveau solde :" + economy.format(guild.getBalance()));
        saveGuildToDB(guild);
    }

    public void guildWithdraw(Player player, double amount) {
        Guild guild = getGuildByPlayer(player);
        Economy economy = plugin.getEconomy();

        if (guild == null) {
            player.sendMessage("§cVous n'avez pas de guilde !");
            return;
        }

        if (economy == null) {
            player.sendMessage("§cLa banque n'est pas accessible.");
            return;
        }

        if (!guild.getMember(player.getUniqueId()).isGranted(Rank.ADMIN)) {
            player.sendMessage("§cVous n'avez pas la permission de retirer de l'argent.");
            return;
        }

        double currentBalance = guild.getBalance();
        if (currentBalance < amount) {
            player.sendMessage("§cIl n'y a pas assez d'argent dans la banque.");
            return;
        }

        economy.depositPlayer(player, amount);
        guild.setBalance(currentBalance - amount);
        player.sendMessage("§aVous avez retiré " + economy.format(amount) + " §ade la banque de votre guilde. " +
                "Nouveau solde :" + economy.format(guild.getBalance()));
        saveGuildToDB(guild);
    }

    public void teleportHome(Player player) {
        Guild guild = getGuildByPlayer(player);

        if (guild == null) {
            player.sendMessage("§cVous n'avez pas de guilde !");
            return;
        }

        if (guild.getHome() == null) {
            player.sendMessage("§cVotre guilde n'a pas de résidence.");
            return;
        }

        CoreBukkitPlugin.getInstance().getTeleportManager().teleport(player, guild.getHome());
    }

    public void setHome(Player player) {
        Guild guild = getGuildByPlayer(player);

        if (guild == null) {
            player.sendMessage("§cVous n'avez pas de guilde !");
            return;
        }

        guild.setHome(SLocationUtils.getSLocation(player.getLocation()));
        saveGuildToDB(guild);
        player.sendMessage("§aVous avez redéfini la position de la résidence de votre guilde.");
    }

    public void delHome(Player player) {
        Guild guild = getGuildByPlayer(player);

        if (guild == null) {
            player.sendMessage("§cVous n'avez pas de guilde !");
            return;
        }

        guild.setHome(null);
        saveGuildToDB(guild);
        player.sendMessage("§aVous supprimé la résidence de votre guilde.");
    }

    public void kick(Player player, OfflinePlayer target) {
        Guild guild = getGuildByPlayer(player);

        if (player.getUniqueId().equals(target.getUniqueId())) {
            player.sendMessage("§cVous ne pouvez pas vous exclure vous même !");
            return;
        }

        if (guild == null) {
            player.sendMessage("§cVous n'avez pas de guilde !");
            return;
        }

        if (!guild.getMember(player.getUniqueId()).isGranted(Rank.ADMIN)) {
            player.sendMessage("§cVous n'avez pas la permission d'exclure des membres de la guilde.");
            return;
        }

        GuildPlayer guildPlayer = guild.getMember(target.getUniqueId());
        if (guildPlayer == null) {
            player.sendMessage("§cCe joueur n'est pas dans votre guilde.");
            return;
        }

        guild.getMembers().remove(guildPlayer.getUuid());
        deleteGuildPlayerFromDB(guildPlayer.getUuid());
        guild.sendMessageToOnlineMembers("§c" + player.getName() + " a été exclu de la guilde.");
        guildPlayer.sendMessageIfOnline("§cVous avez été exclu de la guilde.");
    }

    public void demote(Player player, OfflinePlayer target) {
        Guild guild = getGuildByPlayer(player);

        if (guild == null) {
            player.sendMessage("§cVous n'avez pas de guilde !");
            return;
        }

        if (player.getUniqueId().equals(target.getUniqueId())) {
            player.sendMessage("§cVous ne pouvez pas vous rétrograder vous même !");
            return;
        }

        GuildPlayer guildPlayer = guild.getMember(target.getUniqueId());
        if (guildPlayer == null) {
            player.sendMessage("§cCe joueur n'est pas dans votre guilde.");
            return;
        }

        switch (guildPlayer.getRank()) {
            case MEMBER -> {
                player.sendMessage("§cCe joueur est au rang le plus bas.");
                return;
            }
            case MODERATOR -> {
                if (guild.getMember(player.getUniqueId()).isGranted(Rank.ADMIN)) {
                    guildPlayer.setRank(Rank.MEMBER);
                } else {
                    player.sendMessage("§cIl faut être au moins administrateur pour retrograder un modérateur.");
                    return;
                }
            }
            case ADMIN -> {
                if (guild.getMember(player.getUniqueId()).isGranted(Rank.OWNER)) {
                    guildPlayer.setRank(Rank.MODERATOR);
                } else {
                    player.sendMessage("§cIl faut être fondateur pour rétrograder un administrateur.");
                    return;
                }
            }
            case OWNER -> {
                player.sendMessage("§cLe fondateur ne peut pas être rétrogradé.");
                return;
            }
        }

        saveGuildPlayerToDB(guildPlayer);
        guild.sendMessageToOnlineMembers("§7" + player.getName() + " a été rétrogradé " + guildPlayer.getRank().getName() + ".");
    }

    public void promote(Player player, OfflinePlayer target) {
        Guild guild = getGuildByPlayer(player);

        if (guild == null) {
            player.sendMessage("§cVous n'avez pas de guilde !");
            return;
        }

        if (player.getUniqueId().equals(target.getUniqueId())) {
            player.sendMessage("§cVous ne pouvez pas vous rétrograder vous même !");
            return;
        }

        GuildPlayer guildPlayer = guild.getMember(target.getUniqueId());
        if (guildPlayer == null) {
            player.sendMessage("§cCe joueur n'est pas dans votre guilde.");
            return;
        }

        switch (guildPlayer.getRank()) {
            case MEMBER -> {
                if (guild.getMember(player.getUniqueId()).isGranted(Rank.ADMIN)) {
                    guildPlayer.setRank(Rank.MODERATOR);
                } else {
                    player.sendMessage("§cIl faut être au moins administrateur pour promouvoir quelqu'un modérateur.");
                    return;
                }
            }
            case MODERATOR -> {
                if (guild.getMember(player.getUniqueId()).isGranted(Rank.OWNER)) {
                    guildPlayer.setRank(Rank.ADMIN);
                } else {
                    player.sendMessage("§cIl faut être le fondateur pour promouvoir quelqu'un administrateur.");
                    return;
                }
            }
            case ADMIN -> {
                player.sendMessage("§cVous ne pouvez pas promouvoir un administrateur.");
                if (guild.getMember(player.getUniqueId()).isGranted(Rank.OWNER)) {
                    player.sendMessage("Utilisez /guild transfer pour donner le propriété de la guilde à cette personne.");
                }
                return;
            }
            case OWNER -> {
                player.sendMessage("§cLe fondateur ne peut pas être promu.");
                return;
            }
        }

        saveGuildPlayerToDB(guildPlayer);
        guild.sendMessageToOnlineMembers("§7" + player.getName() + " a été promu " + guildPlayer.getRank().getName() + ".");
    }

    public void transfer(Player player, OfflinePlayer target) {
        Guild guild = getGuildByPlayer(player);

        if (guild == null) {
            player.sendMessage("§cVous n'avez pas de guilde !");
            return;
        }

        GuildPlayer guildOwner = guild.getOwner();
        if (guildOwner.getUuid().equals(player.getUniqueId())) {
            player.sendMessage("§cIl faut être le fondateur pour transférer la proprieté de la guilde.");
            return;
        }

        if (player.getUniqueId().equals(target.getUniqueId())) {
            player.sendMessage("§cLa guilde vous appartient déjà.");
            return;
        }

        GuildPlayer guildPlayer = guild.getMember(target.getUniqueId());
        if (guildPlayer == null) {
            player.sendMessage("§cCe joueur n'est pas dans votre guilde.");
            return;
        }

        guildPlayer.setRank(Rank.OWNER);
        guildOwner.setRank(Rank.ADMIN);
        saveGuildPlayerToDB(guildPlayer);
        saveGuildPlayerToDB(guildOwner);
        guild.sendMessageToOnlineMembers("§7§l" + player.getName() + " a transféré la proprieté de la guilde à " + guildPlayer.getName() + ".");
    }

    /*
    DB UPDATES
     */

    private void saveGuildToDB(Guild guild) {
        plugin.runAsyncQueued(() -> {
            storage.saveGuild(guild);
            syncGuild(guild.getId());
        });
    }

    private void deleteGuildFromDB(UUID guildID) {
        plugin.runAsyncQueued(() -> {
            storage.deleteGuild(guildID);
            syncGuild(guildID);
        });
    }

    private void saveGuildPlayerToDB(GuildPlayer guildPlayer) {
        plugin.runAsyncQueued(() -> {
            storage.saveGuildPlayer(guildPlayer);
            syncGuildPlayer(guildPlayer.getUuid());
        });
    }

    private void deleteGuildPlayerFromDB(UUID uuid) {
        plugin.runAsyncQueued(() -> {
            storage.deleteGuildPlayer(uuid);
            syncGuildPlayer(uuid);
        });
    }


    /*
    SYNC
     */


    public void syncGuild(UUID guildID) {
        CoreBukkitPlugin core = CoreBukkitPlugin.getInstance();
        core.getMessagingManager().sendMessage(GuildsPlugin.GUILD_SYNC_CHANNEL, guildID.toString());
    }

    public void syncGuildPlayer(UUID uuid) {
        CoreBukkitPlugin core = CoreBukkitPlugin.getInstance();
        core.getMessagingManager().sendMessage(GuildsPlugin.GUILD_PLAYER_SYNC_CHANNEL, uuid.toString());
    }

    public void reloadGuildFromDB(UUID guildId) {
        Guild oldGuild = guilds.get(guildId);
        Guild newGuild = storage.getGuild(guildId);

        if (oldGuild != null && newGuild == null) {
            //Suppression d'une guilde
            guilds.remove(guildId);
        } else {
            //Ajout ou maj à jour de la guilde
            guilds.put(guildId, newGuild);
            for (GuildPlayer guildMember : storage.getGuildMembers(guildId)) {
                newGuild.getMembers().put(guildMember.getUuid(), guildMember);
            }
        }
    }

    public void reloadGuildPlayerFromDB(UUID uuid) {
        GuildPlayer oldGuildPlayer = getGuildPlayer(uuid);
        GuildPlayer newGuildPlayer = storage.getGuildPlayer(uuid);
        if (oldGuildPlayer != null && newGuildPlayer == null) {
            //Le joueur a quitté la guilde
            guilds.get(oldGuildPlayer.getGuildId()).getMembers().remove(uuid);
        } else {
            //Le joueur a rejoint une guilde ou a été mis à jour.
            guilds.get(newGuildPlayer.getGuildId()).getMembers().put(uuid, newGuildPlayer);
        }
    }


    /*
    LOAD
     */


    private void loadGuilds() {
        long start = System.currentTimeMillis();
        for (Guild guild : storage.getGuilds()) {
            guilds.put(guild.getId(), guild);
        }
        plugin.getLogger().info(guilds.size() + " guildes chargées en " + (System.currentTimeMillis() - start) + "ms.");
    }

    private void loadGuildPlayers() {
        long start = System.currentTimeMillis();
        List<GuildPlayer> guildPlayers = storage.getGuildPlayers();
        for (GuildPlayer guildPlayer : guildPlayers) {
            UUID uuid = guildPlayer.getUuid();
            guilds.get(guildPlayer.getGuildId()).getMembers().put(uuid, guildPlayer);
        }
        plugin.getLogger().info(guildPlayers.size() + " joueurs de guilde chargées en " + (System.currentTimeMillis() - start) + "ms.");
    }

    public void load() {
        guilds.clear();
        loadGuilds();
        loadGuildPlayers();
    }

}