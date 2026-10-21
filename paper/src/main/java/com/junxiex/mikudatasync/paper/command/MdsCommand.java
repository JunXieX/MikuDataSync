package com.junxiex.mikudatasync.paper.command;

import com.junxiex.mikudatasync.common.CodecBench;
import com.junxiex.mikudatasync.common.Metrics;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import com.junxiex.mikudatasync.common.StageMetrics;
import com.junxiex.mikudatasync.common.StageMetrics;
import com.junxiex.mikudatasync.paper.CaptureService;
import com.junxiex.mikudatasync.paper.IpcClient;
import com.junxiex.mikudatasync.paper.MikuDataSyncPaper;
import com.junxiex.mikudatasync.paper.NmsBridge;
import com.junxiex.mikudatasync.paper.PaperConfig;
import com.junxiex.mikudatasync.paper.PluginSchedulers;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class MdsCommand {

    private MdsCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> node(MikuDataSyncPaper plugin, IpcClient ipcClient,
                                                              PaperConfig config, CaptureService captureService) {
        return Commands.literal("mdsbackend")
                .requires(source -> source.getSender().hasPermission("mikudatasync.admin"))
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().getSender().sendMessage(Component.text(
                            "MikuDataSync | ipc=" + ipcClient.state() + " | server=" + config.serverId()
                                    + " | " + Metrics.captureSummary() + " | " + Metrics.applySummary()));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("timings").executes(context -> {
                    context.getSource().getSender().sendMessage(Component.text(StageMetrics.summary()));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("bench").executes(context -> {
                    CodecBench.Result result = CodecBench.run(200, 2_000);
                    context.getSource().getSender().sendMessage(Component.text(CodecBench.describe(result)));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("selftest").executes(context -> {
                    CommandSender sender = context.getSource().getSender();
                    String bridges = "bridges: cursor=" + NmsBridge.cursorBridgeAvailable()
                            + ", data=" + NmsBridge.dataBridgeAvailable()
                            + ", dataVersion=" + NmsBridge.dataVersion();
                    if (sender instanceof Player player) {
                        PluginSchedulers.runOnEntity(plugin, player, () -> {
                            SnapshotMessage message = captureService.capture(player, "selftest");
                            sender.sendMessage(Component.text(bridges + ", capture="
                                    + (message == null ? "failed" : message.envelope().length + "B")));
                        });
                        sender.sendMessage(Component.text(bridges + " (capture scheduled)"));
                    } else {
                        sender.sendMessage(Component.text(bridges));
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .then(DebugCommand.node(plugin, config))
                .build();
    }
}
