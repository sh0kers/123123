package ru.holyworld.tntbuilder;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Весь цикл:
 * 1) берём Тнт-Пушку и ставим туда, куда смотрит прицел;
 * 2) открываем её и шифт-кликом кладём Динамит B;
 * 3) ставим рядом редстоун блок;
 * 4) ждём конец цикла (в пушке не осталось TNT);
 * 5) ломаем пушку киркой (и потом редстоун блок).
 */
public final class Macro {

    /** Пока true - миксин не даёт ваниле сбрасывать прогресс ломания блока. */
    public static volatile boolean suppressCancel = false;

    private enum Step {
        IDLE,
        EQUIP_CANNON, PLACE_CANNON, VERIFY_CANNON,
        OPEN_FOR_FILL, WAIT_GUI_FILL, FILL, CLOSE_AFTER_FILL,
        EQUIP_REDSTONE, PLACE_REDSTONE, VERIFY_REDSTONE,
        WAIT_CYCLE, OPEN_FOR_CHECK, WAIT_GUI_CHECK, CHECK,
        EQUIP_PICKAXE, BREAK
    }

    private record Spot(BlockPos target, BlockPos support, Direction face, Vec3d hit) {
    }

    private static final int EQUIP_NOT_FOUND = 0;
    private static final int EQUIP_READY = 1;
    private static final int EQUIP_CHANGED = 2;

    private static final double REACH = 4.5;

    /** Блоки, на которые нельзя "опираться" кликом - они откроют GUI или сработают. */
    private static final Set<Block> INTERACTIVE = Set.of(
            Blocks.CRAFTING_TABLE, Blocks.SMITHING_TABLE, Blocks.CARTOGRAPHY_TABLE,
            Blocks.LOOM, Blocks.NOTE_BLOCK, Blocks.DISPENSER, Blocks.DROPPER
    );

    private Step step = Step.IDLE;
    private int wait;
    private int stepTicks;
    private int cycleTicks;
    private int lastPollTick;
    private boolean cyclePhase;

    private int filled;
    private int pendingSlot = -1;
    private int pendingCount;
    private int openRetries;
    private int equipRetries;
    private int airTicks;

    private BlockHitResult startHit;
    private BlockPos cannonPos;
    private BlockPos redstonePos;
    private List<Spot> spots;
    private final Deque<BlockPos> breakQueue = new ArrayDeque<>();

    // ------------------------------------------------------------------ управление

    public void toggle(MinecraftClient mc) {
        if (step != Step.IDLE) {
            ClientPlayerEntity p = mc.player;
            if (p != null) {
                closeGuiIfOpen(p);
                say(p, "Остановлено.", Formatting.YELLOW, false);
            }
            reset(mc);
            return;
        }
        start(mc);
    }

    private void start(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) {
            return;
        }
        if (mc.currentScreen != null) {
            return;
        }
        HitResult hr = mc.crosshairTarget;
        if (hr == null || hr.getType() != HitResult.Type.BLOCK || !(hr instanceof BlockHitResult)) {
            say(p, "Наведи прицел на блок, куда нужно поставить пушку.", Formatting.RED, false);
            return;
        }
        if (p.isSneaking()) {
            say(p, "Отпусти Shift: с Shift пушка не откроется.", Formatting.RED, false);
            return;
        }
        reset(mc);
        startHit = (BlockHitResult) hr;
        say(p, "Запуск...", Formatting.GREEN, true);
        goTo(Step.EQUIP_CANNON, 0);
    }

    private void reset(MinecraftClient mc) {
        suppressCancel = false;
        step = Step.IDLE;
        wait = 0;
        stepTicks = 0;
        cycleTicks = 0;
        lastPollTick = 0;
        cyclePhase = false;
        filled = 0;
        pendingSlot = -1;
        pendingCount = 0;
        openRetries = 0;
        equipRetries = 0;
        airTicks = 0;
        startHit = null;
        cannonPos = null;
        redstonePos = null;
        spots = null;
        breakQueue.clear();
        if (mc != null && mc.interactionManager != null) {
            mc.interactionManager.cancelBlockBreaking();
        }
    }

    private void goTo(Step next, int delay) {
        step = next;
        wait = Math.max(0, delay);
        stepTicks = 0;
    }

    private void fail(MinecraftClient mc, ClientPlayerEntity p, String msg) {
        say(p, msg, Formatting.RED, false);
        closeGuiIfOpen(p);
        reset(mc);
    }

    private void finish(MinecraftClient mc, ClientPlayerEntity p) {
        say(p, "Готово: цикл завершён.", Formatting.GREEN, false);
        reset(mc);
    }

    // ------------------------------------------------------------------ тик

    public void tick(MinecraftClient mc) {
        if (step == Step.IDLE) {
            return;
        }
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (p == null || w == null || mc.interactionManager == null || !p.isAlive()) {
            reset(mc);
            return;
        }
        if (cyclePhase) {
            cycleTicks++;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        // если игрок открыл что-то своё (чат, инвентарь) - ставим макрос на паузу
        if (!isGuiStep(step) && mc.currentScreen != null) {
            return;
        }
        stepTicks++;
        try {
            run(mc, p, w);
        } catch (Throwable t) {
            HolyWorldTntBuilder.LOGGER.error("Ошибка в макросе", t);
            fail(mc, p, "Внутренняя ошибка: " + t);
        }
    }

    private static boolean isGuiStep(Step s) {
        return s == Step.WAIT_GUI_FILL || s == Step.FILL || s == Step.CLOSE_AFTER_FILL
                || s == Step.WAIT_GUI_CHECK || s == Step.CHECK;
    }

    private void run(MinecraftClient mc, ClientPlayerEntity p, ClientWorld w) {
        Config cfg = HolyWorldTntBuilder.CONFIG;
        ClientPlayerInteractionManager im = mc.interactionManager;

        switch (step) {

            // ---------------------------------------------------------- пушка
            case EQUIP_CANNON -> {
                int r = equip(mc, p, Macro::isCannon);
                if (r == EQUIP_NOT_FOUND) {
                    fail(mc, p, "В инвентаре нет Тнт-Пушки (раздатчик с названием \"" + cfg.cannonName + "\").");
                    return;
                }
                goTo(Step.PLACE_CANNON, r == EQUIP_CHANGED ? 3 : 0);
            }

            case PLACE_CANNON -> {
                ItemStack held = p.getMainHandStack();
                if (!isCannon(held)) {
                    if (++equipRetries > 3) {
                        fail(mc, p, "Не получилось взять Тнт-Пушку в руку.");
                    } else {
                        goTo(Step.EQUIP_CANNON, 2);
                    }
                    return;
                }
                if (p.getEyePos().distanceTo(startHit.getPos()) > REACH + 1.0) {
                    fail(mc, p, "Слишком далеко от выбранного блока. Подойди ближе и запусти снова.");
                    return;
                }
                cannonPos = new ItemPlacementContext(p, Hand.MAIN_HAND, held, startHit).getBlockPos();
                im.interactBlock(p, Hand.MAIN_HAND, startHit);
                p.swingHand(Hand.MAIN_HAND);
                goTo(Step.VERIFY_CANNON, cfg.verifyDelayTicks);
            }

            case VERIFY_CANNON -> {
                if (w.getBlockState(cannonPos).isOf(Blocks.DISPENSER)) {
                    openRetries = 0;
                    goTo(Step.OPEN_FOR_FILL, cfg.actionDelayTicks);
                } else if (stepTicks > 20) {
                    fail(mc, p, "Пушка не поставилась (место занято или сервер запретил).");
                }
            }

            // ---------------------------------------------------------- загрузка динамита
            case OPEN_FOR_FILL, OPEN_FOR_CHECK -> {
                boolean check = step == Step.OPEN_FOR_CHECK;
                if (!w.getBlockState(cannonPos).isOf(Blocks.DISPENSER)) {
                    if (check) {
                        say(p, "Пушки на месте уже нет, ломать нечего.", Formatting.YELLOW, false);
                        finish(mc, p);
                    } else {
                        fail(mc, p, "Пушка пропала после установки.");
                    }
                    return;
                }
                boolean tooFar = p.getEyePos().distanceTo(Vec3d.ofCenter(cannonPos)) > REACH;
                if (p.isSneaking() || tooFar) {
                    if (check) {
                        lastPollTick = cycleTicks;
                        goTo(Step.WAIT_CYCLE, 0);
                    } else {
                        fail(mc, p, p.isSneaking()
                                ? "Отпусти Shift: с Shift пушка не откроется."
                                : "Слишком далеко от пушки.");
                    }
                    return;
                }
                useCannon(mc, p);
                goTo(check ? Step.WAIT_GUI_CHECK : Step.WAIT_GUI_FILL, 2);
            }

            case WAIT_GUI_FILL -> {
                if (guiOpen(p)) {
                    filled = 0;
                    pendingSlot = -1;
                    goTo(Step.FILL, cfg.actionDelayTicks);
                } else if (stepTicks > 40) {
                    if (++openRetries > 2) {
                        fail(mc, p, "Окно пушки не открылось. Подойди ближе / отпусти Shift.");
                    } else {
                        goTo(Step.OPEN_FOR_FILL, 5);
                    }
                }
            }

            case FILL -> {
                if (!guiOpen(p)) {
                    fail(mc, p, "Окно пушки закрылось раньше времени.");
                    return;
                }
                ScreenHandler h = p.currentScreenHandler;
                int cs = containerSize(h);

                if (pendingSlot >= 0) {
                    ItemStack cur = h.slots.get(pendingSlot).getStack();
                    if (isTnt(cur) && cur.getCount() >= pendingCount) {
                        // стак не сдвинулся - пушка заполнена
                        goTo(Step.CLOSE_AFTER_FILL, 6);
                        return;
                    }
                    pendingSlot = -1;
                }

                int src = -1;
                if (filled < cfg.maxTntStacks) {
                    for (int i = cs; i < h.slots.size(); i++) {
                        if (isTnt(h.slots.get(i).getStack())) {
                            src = i;
                            break;
                        }
                    }
                }
                if (src < 0) {
                    if (filled == 0) {
                        fail(mc, p, "Динамит B не найден в инвентаре (ищу название \"" + cfg.tntName + "\").");
                    } else {
                        goTo(Step.CLOSE_AFTER_FILL, 6);
                    }
                    return;
                }
                pendingSlot = src;
                pendingCount = h.slots.get(src).getStack().getCount();
                im.clickSlot(h.syncId, src, 0, SlotActionType.QUICK_MOVE, p);
                filled++;
                wait = cfg.clickDelayTicks;
            }

            case CLOSE_AFTER_FILL -> {
                if (guiOpen(p)) {
                    boolean has = containerHasTnt(p.currentScreenHandler);
                    p.closeHandledScreen();
                    if (!has) {
                        fail(mc, p, "Динамит не остался в пушке (сервер не принял). Проверь tntName в конфиге.");
                        return;
                    }
                }
                goTo(Step.EQUIP_REDSTONE, cfg.actionDelayTicks);
            }

            // ---------------------------------------------------------- редстоун блок
            case EQUIP_REDSTONE -> {
                int r = equip(mc, p, s -> !s.isEmpty() && s.isOf(Items.REDSTONE_BLOCK));
                if (r == EQUIP_NOT_FOUND) {
                    fail(mc, p, "В инвентаре нет редстоун блока.");
                    return;
                }
                goTo(Step.PLACE_REDSTONE, r == EQUIP_CHANGED ? 3 : 0);
            }

            case PLACE_REDSTONE -> {
                if (!p.getMainHandStack().isOf(Items.REDSTONE_BLOCK)) {
                    if (++equipRetries > 6) {
                        fail(mc, p, "Не получилось взять редстоун блок в руку.");
                    } else {
                        goTo(Step.EQUIP_REDSTONE, 2);
                    }
                    return;
                }
                if (spots == null) {
                    spots = findRedstoneSpots(w, p);
                }
                if (spots.isEmpty()) {
                    fail(mc, p, "Рядом с пушкой нет места для редстоун блока.");
                    return;
                }
                Spot s = spots.remove(0);
                im.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(s.hit(), s.face(), s.support(), false));
                p.swingHand(Hand.MAIN_HAND);
                redstonePos = s.target();
                goTo(Step.VERIFY_REDSTONE, cfg.verifyDelayTicks);
            }

            case VERIFY_REDSTONE -> {
                if (w.getBlockState(redstonePos).isOf(Blocks.REDSTONE_BLOCK)) {
                    cyclePhase = true;
                    cycleTicks = 0;
                    lastPollTick = 0;
                    say(p, "Пушка заряжена и запущена, жду конец цикла...", Formatting.AQUA, true);
                    goTo(Step.WAIT_CYCLE, 0);
                } else if (stepTicks > 15) {
                    if (spots != null && !spots.isEmpty()) {
                        goTo(Step.PLACE_REDSTONE, 2);
                    } else {
                        fail(mc, p, "Редстоун блок не поставился.");
                    }
                }
            }

            // ---------------------------------------------------------- ожидание цикла
            case WAIT_CYCLE -> {
                if (!cfg.waitForEmpty) {
                    if (cycleTicks >= cfg.fixedWaitSeconds * 20) {
                        endCycle(cfg);
                    }
                    return;
                }
                if (cycleTicks >= cfg.maxWaitSeconds * 20) {
                    say(p, "Время ожидания вышло, ломаю пушку.", Formatting.YELLOW, false);
                    endCycle(cfg);
                    return;
                }
                if (cycleTicks - lastPollTick >= cfg.pollSeconds * 20) {
                    lastPollTick = cycleTicks;
                    goTo(Step.OPEN_FOR_CHECK, 0);
                }
            }

            case WAIT_GUI_CHECK -> {
                if (guiOpen(p)) {
                    goTo(Step.CHECK, 3);
                } else if (stepTicks > 30) {
                    lastPollTick = cycleTicks;
                    goTo(Step.WAIT_CYCLE, 0);
                }
            }

            case CHECK -> {
                if (!guiOpen(p)) {
                    goTo(Step.WAIT_CYCLE, 0);
                    return;
                }
                boolean has = containerHasTnt(p.currentScreenHandler);
                p.closeHandledScreen();
                if (has) {
                    lastPollTick = cycleTicks;
                    goTo(Step.WAIT_CYCLE, 5);
                } else {
                    say(p, "Динамит в пушке закончился, ломаю её.", Formatting.AQUA, true);
                    endCycle(cfg);
                }
            }

            // ---------------------------------------------------------- ломаем
            case EQUIP_PICKAXE -> {
                suppressCancel = false;
                int r = equip(mc, p, Macro::isPickaxe);
                if (r == EQUIP_NOT_FOUND) {
                    fail(mc, p, "В инвентаре нет кирки.");
                    return;
                }
                breakQueue.clear();
                breakQueue.add(cannonPos);
                if (cfg.breakRedstoneBlock && redstonePos != null) {
                    breakQueue.add(redstonePos);
                }
                airTicks = 0;
                goTo(Step.BREAK, r == EQUIP_CHANGED ? 3 : 0);
            }

            case BREAK -> {
                BlockPos t = breakQueue.peek();
                if (t == null) {
                    finish(mc, p);
                    return;
                }
                if (!isPickaxe(p.getMainHandStack())) {
                    suppressCancel = false;
                    goTo(Step.EQUIP_PICKAXE, 1);
                    return;
                }
                BlockState st = w.getBlockState(t);
                if (st.isAir()) {
                    // ждём несколько тиков: вдруг сервер вернёт блок обратно
                    if (++airTicks >= 6) {
                        breakQueue.poll();
                        airTicks = 0;
                        stepTicks = 0;
                        suppressCancel = false;
                        im.cancelBlockBreaking();
                    }
                    return;
                }
                airTicks = 0;
                Vec3d eye = p.getEyePos();
                Vec3d c = Vec3d.ofCenter(t);
                if (eye.distanceTo(c) > REACH + 0.5) {
                    fail(mc, p, "Слишком далеко, чтобы сломать блок. Подойди ближе.");
                    return;
                }
                if (stepTicks > cfg.breakTimeoutTicks) {
                    fail(mc, p, "Не получается сломать блок (возможно, сервер запрещает).");
                    return;
                }
                suppressCancel = true;
                Direction dir = Direction.getFacing(eye.x - c.x, eye.y - c.y, eye.z - c.z);
                im.updateBlockBreakingProgress(t, dir);
                p.swingHand(Hand.MAIN_HAND);
            }

            default -> {
            }
        }
    }

    private void endCycle(Config cfg) {
        cyclePhase = false;
        goTo(Step.EQUIP_PICKAXE, cfg.actionDelayTicks);
    }

    // ------------------------------------------------------------------ вспомогательное

    private void useCannon(MinecraftClient mc, ClientPlayerEntity p) {
        Vec3d eye = p.getEyePos();
        Vec3d c = Vec3d.ofCenter(cannonPos);
        Direction face = Direction.getFacing(eye.x - c.x, eye.y - c.y, eye.z - c.z);
        Vec3d hit = c.add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
        mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, face, cannonPos, false));
    }

    /** Ищет места для редстоун блока рядом с пушкой (не перед её "дулом"). */
    private List<Spot> findRedstoneSpots(ClientWorld w, ClientPlayerEntity p) {
        List<Spot> out = new ArrayList<>();
        BlockState cs = w.getBlockState(cannonPos);
        Direction front = cs.contains(Properties.FACING) ? cs.get(Properties.FACING) : null;
        Vec3d eye = p.getEyePos();

        Direction[] targets = {
                Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP, Direction.DOWN
        };
        Direction[] supports = {
                Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP
        };

        for (Direction d : targets) {
            if (d == front) {
                continue;
            }
            BlockPos n = cannonPos.offset(d);
            if (!w.getBlockState(n).isReplaceable()) {
                continue;
            }
            if (p.getBoundingBox().intersects(new Box(n))) {
                continue;
            }
            for (Direction f : supports) {
                BlockPos b = n.offset(f);
                if (b.equals(cannonPos)) {
                    continue;
                }
                BlockState bs = w.getBlockState(b);
                if (bs.isAir() || bs.hasBlockEntity() || INTERACTIVE.contains(bs.getBlock())
                        || !bs.isFullCube(w, b)) {
                    continue;
                }
                Direction face = f.getOpposite();
                Vec3d hit = Vec3d.ofCenter(b).add(
                        face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
                if (eye.distanceTo(hit) > REACH) {
                    continue;
                }
                out.add(new Spot(n, b, face, hit));
                break;
            }
        }
        return out;
    }

    /** Берёт нужный предмет в руку (из хотбара - выбирает слот, из инвентаря - меняет местами). */
    private int equip(MinecraftClient mc, ClientPlayerEntity p, Predicate<ItemStack> pred) {
        if (pred.test(p.getMainHandStack())) {
            return EQUIP_READY;
        }
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            if (pred.test(inv.getStack(i))) {
                inv.selectedSlot = i;
                return EQUIP_CHANGED;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (pred.test(inv.getStack(i))) {
                mc.interactionManager.clickSlot(
                        p.playerScreenHandler.syncId, i, inv.selectedSlot, SlotActionType.SWAP, p);
                return EQUIP_CHANGED;
            }
        }
        return EQUIP_NOT_FOUND;
    }

    private static boolean guiOpen(ClientPlayerEntity p) {
        return p.currentScreenHandler != null && p.currentScreenHandler != p.playerScreenHandler;
    }

    private static void closeGuiIfOpen(ClientPlayerEntity p) {
        if (guiOpen(p)) {
            p.closeHandledScreen();
        }
    }

    /** Размер контейнера = все слоты минус 36 слотов инвентаря игрока (для раздатчика = 9). */
    private static int containerSize(ScreenHandler h) {
        return Math.max(0, h.slots.size() - 36);
    }

    private static boolean containerHasTnt(ScreenHandler h) {
        int cs = containerSize(h);
        for (int i = 0; i < cs; i++) {
            if (h.slots.get(i).getStack().isOf(Items.TNT)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCannon(ItemStack s) {
        if (s.isEmpty() || !s.isOf(Items.DISPENSER)) {
            return false;
        }
        String need = norm(HolyWorldTntBuilder.CONFIG.cannonName);
        return need.isEmpty() || norm(s.getName().getString()).contains(need);
    }

    private static boolean isTnt(ItemStack s) {
        if (s.isEmpty() || !s.isOf(Items.TNT)) {
            return false;
        }
        String need = norm(HolyWorldTntBuilder.CONFIG.tntName);
        return need.isEmpty() || norm(s.getName().getString()).contains(need);
    }

    private static boolean isPickaxe(ItemStack s) {
        return !s.isEmpty() && Registries.ITEM.getId(s.getItem()).getPath().endsWith("_pickaxe");
    }

    private static final String CYR = "\u0430\u0432\u0435\u043a\u043c\u043d\u043e\u0440\u0441\u0442\u0445\u0443\u0451";
    private static final String LAT = "abekmhopctxye";

    /** Нижний регистр, без пробелов, кириллические буквы-двойники заменены латинскими (В == B). */
    private static String norm(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isWhitespace(c)) {
                continue;
            }
            int idx = CYR.indexOf(c);
            sb.append(idx >= 0 ? LAT.charAt(idx) : c);
        }
        return sb.toString();
    }

    private static void say(ClientPlayerEntity p, String msg, Formatting color, boolean overlay) {
        p.sendMessage(Text.literal("[TNT Builder] " + msg).formatted(color), overlay);
    }
}
