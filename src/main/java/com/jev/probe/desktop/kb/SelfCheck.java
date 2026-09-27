package com.jev.probe.desktop.kb;

import com.jev.probe.desktop.config.Config;
import com.jev.probe.desktop.model.ChatSnapshot;
import com.jev.probe.desktop.model.Msg;
import com.jev.probe.desktop.prompt.JudgePrompts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Smoke test for the knowledge-base path, reachable from the settings screen
 * (「自检」). It exercises the parts that are easy to get quietly wrong — name
 * normalization across half/full-width member counts, note keyword hits, and
 * history de-duplication against what is already on screen.
 *
 * <p>Desktop port of the Android `KbSelfCheck`, with one deliberate difference:
 * it runs over a **scratch directory** instead of the user's real store. Android
 * can only do it against the real one because its `ContextBuilder` takes the
 * store implicitly from the app context; here [ContextBuilder#build] has an
 * overload that takes the store, so the check can be genuinely non-destructive —
 * nothing it creates is ever visible to the real knowledge base, even if the
 * process dies mid-run.
 *
 * <p>The config is a scratch file for the same reason, which also means the
 * user's own `context_enabled` setting is never touched.
 */
public final class SelfCheck {

    private static final Logger LOGGER = Logger.getLogger("AICHAT");

    private static final String BARE = "测试群";
    private static final String TITLE = "测试群(12)";
    /** Full-width parens on purpose — the normalization must fold them. */
    private static final String ALIAS = "测试群（12）";
    private static final String PADDED = "  测试群  ";
    private static final String OLD_LINE = "上周说好周五交自检稿";
    private static final String NOTE_FACT = "小蓝";
    private static final String CONTACT_REL = "自检用的关系描述";
    /** The app this check pretends to run in; the contact is filed under it. */
    private static final String APP = "com.jev.probe";

    private SelfCheck() {
    }

    /** @return a one-line human-readable pass/fail summary. */
    public static String run() {
        List<String> failures = new ArrayList<>();

        // 0. Name normalization, checked first and on its own: it is the piece
        //    whose failure would take down every analysis, not just the KB.
        try {
            for (String raw : List.of(TITLE, ALIAS, PADDED, BARE)) {
                String got = KbStore.normalizeName(raw);
                if (!BARE.equals(got)) {
                    failures.add("名称归一化失败：输入 " + raw + " 得到 " + got + "，应为 " + BARE);
                }
            }
        } catch (Exception e) {
            failures.add("名称归一化异常：" + describe(e));
        }

        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("aichat-selfcheck");
        } catch (IOException e) {
            return "自检失败（1）：无法创建临时目录 " + describe(e);
        }

        KbStore store = KbStore.get(scratch.resolve("kb"));
        Config prefs = new Config(scratch.resolve("config.json"));
        String noteId = KbStore.newId();
        String contactId = KbStore.newId();
        try {
            prefs.contextEnabled(true);
            prefs.contextHistoryCount(30);

            store.saveNote(new Note(noteId, "自检临时笔记",
                "自检用的虚构事实：项目代号叫" + NOTE_FACT + "。",
                List.of("测试"), false, true, System.currentTimeMillis()));
            store.saveContact(new Contact(contactId, "自检临时联系人",
                List.of(ALIAS), List.of(APP), CONTACT_REL, "", "", System.currentTimeMillis()));

            ChatSnapshot snapshot = new ChatSnapshot(TITLE, List.of(
                new Msg("other", "自检消息一：这条够长可以去重"),
                new Msg("me", "自检消息二：这条也够长")));

            // 1. Contact hit via the full-width alias + member-count stripping.
            ChatContext ctx1 = ContextBuilder.build(snapshot, APP, prefs, store);
            if (ctx1.contact() == null || !contactId.equals(ctx1.contact().id())) {
                failures.add("联系人未命中（标题 " + TITLE + " 应匹配别名 " + ALIAS + "）");
            }

            // 2. Note hit via the tag 「测试」, which the conversation title carries.
            if (ctx1.notes().stream().noneMatch(n -> noteId.equals(n.id()))) {
                failures.add("笔记未命中（tag=测试 应命中标题 " + TITLE + "）");
            }

            // 3. The on-screen messages were recorded but not echoed back.
            if (!ctx1.history().isEmpty()) {
                failures.add("历史去重失败：当屏消息不该出现在注入历史里（"
                    + ctx1.history().size() + " 条）");
            }
            if (store.logSize(contactId) != snapshot.messages().size()) {
                failures.add("历史落盘条数不对：期望 " + snapshot.messages().size()
                    + "，实际 " + store.logSize(contactId));
            }

            // 4. An older line survives, and re-reading the same screen adds
            //    nothing. screenBatch=false: this is a hand-injected line, not a
            //    capture, so it is not measured against the last screen recorded.
            store.appendLog(contactId, List.of(new LogEntry(
                "other", OLD_LINE, System.currentTimeMillis() - 86_400_000L, APP)), false);
            ChatContext ctx2 = ContextBuilder.build(snapshot, APP, prefs, store);
            if (ctx2.history().size() != 1
                || !OLD_LINE.equals(ctx2.history().get(0).text())) {
                failures.add("历史注入不对：期望仅 1 条旧消息，实际 " + ctx2.history().size() + " 条");
            }
            if (store.logSize(contactId) != snapshot.messages().size() + 1) {
                failures.add("重复采集被写了第二遍：" + store.logSize(contactId) + " 条");
            }

            // 5. The note reaches the prompt, and the relationship reaches it
            //    EXACTLY ONCE. Regression guard for R-3: the relation used to be
            //    emitted both by ChatContext.background() and by
            //    JudgePrompts.renderUserMessage(), so a contact with a
            //    relationship set printed 「关系：…」 twice.
            String background = ctx2.background();
            if (!background.contains(NOTE_FACT)) failures.add("background 里没有笔记正文");
            String prompt = JudgePrompts.renderUserMessage(
                new ChatSnapshot(TITLE, List.of(new Msg("other", "在吗"))),
                CONTACT_REL, background, ctx2.history(), 10);
            int first = prompt.indexOf(CONTACT_REL);
            if (first < 0) failures.add("提示词里没有联系人关系");
            else if (prompt.indexOf(CONTACT_REL, first + 1) >= 0) {
                failures.add("联系人关系被注入了两次");
            }

            // 6. History is opt-in: with the switch off, nothing is injected.
            prefs.contextEnabled(false);
            if (!ContextBuilder.build(snapshot, APP, prefs, store).history().isEmpty()) {
                failures.add("contextEnabled=false 时仍注入了历史");
            }
        } catch (Exception e) {
            failures.add("异常：" + describe(e));
        } finally {
            deleteQuietly(scratch);
        }

        KbCounts counts = KbStore.get().counts();
        String tail = "当前知识库 " + counts.notes() + " 条笔记、"
            + counts.contacts() + " 个联系人、" + counts.logLines() + " 条历史。";
        if (failures.isEmpty()) {
            return "自检通过：联系人匹配 / 笔记命中 / 历史去重 / 预算注入都正常。" + tail;
        }
        failures.sort(Comparator.naturalOrder());
        return "自检失败（" + failures.size() + "）：" + String.join("；", failures);
    }

    /** Removes the scratch tree; a failure here must not mask the result. */
    private static void deleteQuietly(Path root) {
        if (root == null) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "selfcheck cleanup skipped " + p, e);
                }
            });
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "selfcheck cleanup failed", e);
        }
    }

    private static String describe(Exception e) {
        return e.getClass().getSimpleName()
            + (e.getMessage() == null ? "" : " " + e.getMessage());
    }
}
