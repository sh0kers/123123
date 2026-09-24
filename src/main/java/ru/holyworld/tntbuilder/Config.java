package ru.holyworld.tntbuilder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/** Настройки лежат в .minecraft/config/holyworld-tntbuilder.json */
public final class Config {
    /** Часть названия предмета "Тнт-Пушка" (регистр и раскладка не важны). */
    public String cannonName = "\u0442\u043d\u0442-\u043f\u0443\u0448\u043a\u0430";
    /** Часть названия предмета "Динамит B" (латинская B и русская В считаются одинаковыми). */
    public String tntName = "\u0434\u0438\u043d\u0430\u043c\u0438\u0442 b";

    /** true - ждать, пока в пушке не останется TNT (периодически открывает пушку и смотрит). */
    public boolean waitForEmpty = true;
    /** Если waitForEmpty=false - просто ждать столько секунд. */
    public int fixedWaitSeconds = 10;
    /** Максимум секунд ожидания конца цикла, потом пушка ломается в любом случае. */
    public int maxWaitSeconds = 120;
    /** Как часто (сек) открывать пушку для проверки. */
    public int pollSeconds = 3;

    /** Сколько стаков динамита максимум класть в пушку. */
    public int maxTntStacks = 9;
    /** Ломать ли редстоун блок после пушки (чтобы он вернулся в инвентарь). */
    public boolean breakRedstoneBlock = true;

    /** Пауза между шагами (тики, 20 тиков = 1 сек). */
    public int actionDelayTicks = 4;
    /** Пауза между кликами по слотам (тики). */
    public int clickDelayTicks = 2;
    /** Через сколько тиков после установки проверять, что блок реально встал. */
    public int verifyDelayTicks = 8;
    /** Таймаут ломания одного блока (тики). */
    public int breakTimeoutTicks = 300;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static Config load() {
        Config cfg = new Config();
        Path path = FabricLoader.getInstance().getConfigDir().resolve("holyworld-tntbuilder.json");
        try {
            if (Files.exists(path)) {
                try (Reader r = Files.newBufferedReader(path)) {
                    Config loaded = GSON.fromJson(r, Config.class);
                    if (loaded != null) {
                        cfg = loaded;
                    }
                }
            }
            cfg.sanitize();
            Files.writeString(path, GSON.toJson(cfg));
        } catch (Exception e) {
            HolyWorldTntBuilder.LOGGER.error("Не удалось прочитать/записать конфиг, используются значения по умолчанию", e);
            cfg = new Config();
        }
        return cfg;
    }

    private void sanitize() {
        if (cannonName == null) cannonName = "";
        if (tntName == null) tntName = "";
        fixedWaitSeconds = Math.max(1, fixedWaitSeconds);
        maxWaitSeconds = Math.max(5, maxWaitSeconds);
        pollSeconds = Math.max(1, pollSeconds);
        maxTntStacks = Math.max(1, Math.min(54, maxTntStacks));
        actionDelayTicks = Math.max(1, actionDelayTicks);
        clickDelayTicks = Math.max(1, clickDelayTicks);
        verifyDelayTicks = Math.max(2, verifyDelayTicks);
        breakTimeoutTicks = Math.max(40, breakTimeoutTicks);
    }
}
