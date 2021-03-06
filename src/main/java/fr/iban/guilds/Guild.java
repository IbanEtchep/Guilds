package fr.iban.guilds;

import fr.iban.bukkitcore.CoreBukkitPlugin;
import fr.iban.common.teleport.SLocation;
import fr.iban.guilds.enums.Rank;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Guild {

    private UUID id;
    private String name;
    private final Map<UUID, GuildPlayer> members = new ConcurrentHashMap<>();
    private double balance;
    private long exp;
    private SLocation home;
    private Date createdAt;
    private final List<UUID> invites = new ArrayList<>();

    public Guild(UUID id, String name, double balance, long exp, Date createdAt) {
        this.id = id;
        this.name = name;
        this.balance = balance;
        this.exp = exp;
        this.createdAt = createdAt;
    }

    public Guild(String name) {
        this(UUID.randomUUID(), name, 0, 0, new Date());
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<UUID, GuildPlayer> getMembers() {
        return members;
    }

    public GuildPlayer getOwner() {
        return getMembers().values().stream()
                .filter(guildPlayer -> guildPlayer.getRank() == Rank.OWNER)
                .findFirst().orElse(null);
    }

    public GuildPlayer getMember(UUID uuid) {
        return members.get(uuid);
    }

    public double getBalance() {
        return balance;
    }

    public void setBalance(double balance) {
        this.balance = balance;
    }

    public long getExp() {
        return exp;
    }

    public void setExp(long exp) {
        this.exp = exp;
    }

    public SLocation getHome() {
        return home;
    }

    public void setHome(SLocation home) {
        this.home = home;
    }

    public void sendMessageToOnlineMembers(String message) {
        getMembers().values().forEach(member -> member.sendMessageIfOnline(message));
    }

    public List<UUID> getInvites() {
        return invites;
    }

    public String getDate() {
        return new SimpleDateFormat("dd/MM/yy HH:mm:ss").format(createdAt);
    }
}
