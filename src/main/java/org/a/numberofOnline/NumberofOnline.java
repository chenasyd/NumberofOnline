package org.a.numberofOnline;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import com.google.common.collect.Iterables;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class NumberofOnline extends JavaPlugin implements PluginMessageListener {

    private Map<String, Integer> serverPlayerCounts = new ConcurrentHashMap<>();
    private Map<String, List<String>> serverGroups = new ConcurrentHashMap<>();
    private List<String> configuredServers;
    private int updateInterval;
    private int totalNetworkPlayers = 0;
    private boolean loggingEnabled = true;

    @Override
    public void onEnable() {
        // 保存默认配置
        saveDefaultConfig();
        
        // 加载配置
        loadConfig();
        
        // 注册BungeeCord消息通道
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        getServer().getMessenger().registerIncomingPluginChannel(this, "BungeeCord", this);
        
        // 注册命令
        getCommand("numberofonline").setExecutor(this);
        
        // 注册 PlaceholderAPI 扩展
        new PlayerCountExpansion(this).register();
        
        // 启动定时任务，定期更新玩家数量
        startUpdateTask();
        
        getServer().getConsoleSender().sendMessage("§a[NumberofOnline] 插件已启用");
    }

    @Override
    public void onDisable() {
        // 注销BungeeCord消息通道
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, "BungeeCord", this);
        
        getServer().getConsoleSender().sendMessage("§c[NumberofOnline] 插件已禁用");
    }
    
    private void loadConfig() {
        FileConfiguration config = getConfig();
        configuredServers = config.getStringList("servers");
        updateInterval = config.getInt("update-interval", 10);
        loggingEnabled = config.getBoolean("logging.enable", true);
        
        // 初始化服务器玩家数量映射
        for (String server : configuredServers) {
            serverPlayerCounts.put(server, 0);
        }
        
        // 加载服务器群组配置
        ConfigurationSection groupsSection = config.getConfigurationSection("groups");
        if (groupsSection != null) {
            for (String groupName : groupsSection.getKeys(false)) {
                List<String> servers = config.getStringList("groups." + groupName);
                serverGroups.put(groupName, servers);
                if (loggingEnabled) {
                    getLogger().info("加载群组 " + groupName + "，包含服务器: " + String.join(", ", servers));
                }
            }
        }
    }
    
    private void startUpdateTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                updatePlayerCounts();
            }
        }.runTaskTimerAsynchronously(this, 20L, updateInterval * 20L);
    }
    
    private void updatePlayerCounts() {
        // 获取总网络玩家数
        requestPlayerCount("ALL");
        
        // 获取每个服务器的玩家数
        for (String server : configuredServers) {
            requestPlayerCount(server);
        }
    }
    
    private void requestPlayerCount(String server) {
        Player player = Iterables.getFirst(getServer().getOnlinePlayers(), null);
        if (player == null) {
            // 如果没有在线玩家，无法发送插件消息
            if (loggingEnabled) {
                getLogger().log(Level.INFO, "无法获取玩家数量：没有在线玩家来发送请求");
            }
            return;
        }
        
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("PlayerCount");
        out.writeUTF(server);
        player.sendPluginMessage(this, "BungeeCord", out.toByteArray());
    }
    
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals("BungeeCord")) {
            return;
        }
        
        ByteArrayDataInput in = ByteStreams.newDataInput(message);
        String subchannel = in.readUTF();
        
        if (subchannel.equals("PlayerCount")) {
            String server = in.readUTF();
            int playerCount = in.readInt();
            
            if (server.equals("ALL")) {
                totalNetworkPlayers = playerCount;
                if (loggingEnabled) {
                    getLogger().log(Level.INFO, "总网络玩家数: " + playerCount);
                }
            } else {
                serverPlayerCounts.put(server, playerCount);
                if (loggingEnabled) {
                    getLogger().log(Level.INFO, "服务器 " + server + " 玩家数: " + playerCount);
                }
            }
        }
    }
    
    // 获取特定服务器的玩家数
    public int getServerPlayerCount(String serverName) {
        return serverPlayerCounts.getOrDefault(serverName, 0);
    }
    
    // 获取总网络玩家数
    public int getTotalNetworkPlayers() {
        return totalNetworkPlayers;
    }
    
    // 获取群组的玩家数
    public int getGroupPlayerCount(String groupName) {
        List<String> servers = serverGroups.get(groupName);
        if (servers == null) {
            return 0;
        }
        
        int total = 0;
        for (String server : servers) {
            total += getServerPlayerCount(server);
        }
        return total;
    }
    
    // 切换日志开关
    public void toggleLogging() {
        loggingEnabled = !loggingEnabled;
        getConfig().set("logging.enable", loggingEnabled);
        saveConfig();
    }
    
    // 命令处理
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("numberofonline")) {
            if (args.length > 0) {
                if (args[0].equalsIgnoreCase("reload")) {
                    if (sender.hasPermission("numberofonline.reload")) {
                        reloadConfig();
                        loadConfig();
                        sender.sendMessage("§a[NumberofOnline] 配置已重新加载");
                        return true;
                    } else {
                        sender.sendMessage("§c你没有权限执行此命令");
                        return true;
                    }
                } else if (args[0].equalsIgnoreCase("logging")) {
                    if (sender.hasPermission("numberofonline.logging")) {
                        toggleLogging();
                        sender.sendMessage("§a[NumberofOnline] 日志输出已" + (loggingEnabled ? "启用" : "禁用"));
                        return true;
                    } else {
                        sender.sendMessage("§c你没有权限执行此命令");
                        return true;
                    }
                }
            }
            
            // 显示帮助信息
            sender.sendMessage("§6===== NumberofOnline 帮助 =====");
            sender.sendMessage("§e/numberofonline reload §7- 重新加载配置");
            sender.sendMessage("§e/numberofonline logging §7- 切换日志输出");
            return true;
        }
        return false;
    }

    // PlaceholderAPI 扩展类
    public class PlayerCountExpansion extends PlaceholderExpansion {
        private final NumberofOnline plugin;

        public PlayerCountExpansion(NumberofOnline plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean canRegister() {
            return true;
        }

        @Override
        public String getIdentifier() {
            return "numberofonline";
        }

        @Override
        public String getAuthor() {
            return "YourName";
        }

        @Override
        public String getVersion() {
            return "1.0-SNAPSHOT";
        }

        @Override
        public String onPlaceholderRequest(org.bukkit.entity.Player player, String identifier) {
            // 获取当前服务器总玩家数
            if (identifier.equals("total_players")) {
                return String.valueOf(plugin.getServer().getOnlinePlayers().size());
            }
            
            // 获取整个网络的总玩家数
            if (identifier.equals("network_total")) {
                return String.valueOf(plugin.getTotalNetworkPlayers());
            }
            
            // 获取特定服务器的玩家数，格式: server_服务器名
            if (identifier.startsWith("server_")) {
                String serverName = identifier.substring(7); // 移除 "server_" 前缀
                return String.valueOf(plugin.getServerPlayerCount(serverName));
            }
            
            // 获取群组的玩家数，格式: group_群组名
            if (identifier.startsWith("group_")) {
                String groupName = identifier.substring(6); // 移除 "group_" 前缀
                return String.valueOf(plugin.getGroupPlayerCount(groupName));
            }
            
            return null;
        }
    }
}