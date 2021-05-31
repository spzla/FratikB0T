/*
 * Copyright (C) 2019-2021 FratikB0T Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package pl.fratik.core.util;

import com.google.common.eventbus.EventBus;
import io.sentry.Sentry;
import lombok.Getter;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.interaction.ButtonClickEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.exceptions.PermissionException;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.Button;
import net.dv8tion.jda.api.requests.restaction.MessageAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.fratik.core.event.PluginMessageEvent;
import pl.fratik.core.tlumaczenia.Language;
import pl.fratik.core.tlumaczenia.Tlumaczenia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

public class DynamicEmbedPaginator implements EmbedPaginator {
    private static final String FIRST_EMOJI = "\u23EE";
    private static final String LEFT_EMOJI = "\u25C0";
    private static final String RIGHT_EMOJI = "\u25B6";
    private static final String LAST_EMOJI = "\u23ED";
    private static final String STOP_EMOJI = "\u23F9";
    private static final String ONETWOTHREEFOUR_EMOJI = "\uD83D\uDD22";
    private static final String SHUFFLE_EMOJI = "\uD83D\uDD00";
    private static final String TRASH_EMOJI = "\uD83D\uDDD1";

    private final EventWaiter eventWaiter;
    private final List<FutureTask<EmbedBuilder>> pages;
    private final EventBus eventBus;
    private int pageNo = 1;
    private volatile Message message;
    private Message doKtorej;
    private long messageId = 0;
    private final long userId;
    private final Language language;
    private final Tlumaczenia tlumaczenia;
    private boolean customFooter;
    private boolean enableShuffle;
    private boolean enableDelett;
    private long timeout = 30;
    private boolean loading = true;
    private boolean ended = false;
    private boolean preload = true;
    private static final String PMSTO = "moderation";
    private static final String PMZAADD = "znaneAkcje-add:";
    private static final ExecutorService mainExecutor = Executors.newFixedThreadPool(4);
    private static final Logger LOGGER = LoggerFactory.getLogger(DynamicEmbedPaginator.class);

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(mainExecutor::shutdown));
    }

    public DynamicEmbedPaginator(EventWaiter eventWaiter, List<FutureTask<EmbedBuilder>> pages, User user, Language language, Tlumaczenia tlumaczenia, EventBus eventBus) {
        this(eventWaiter, pages, user, language, tlumaczenia, eventBus, true);
    }

    public DynamicEmbedPaginator(EventWaiter eventWaiter, List<FutureTask<EmbedBuilder>> pages, User user, Language language, Tlumaczenia tlumaczenia, EventBus eventBus, boolean preload) {
        this.preload = preload;
        this.eventWaiter = eventWaiter;
        this.pages = pages;
        this.eventBus = eventBus;
        if (pages.isEmpty()) throw new IllegalArgumentException("brak stron");
        this.userId = user.getIdLong();
        this.language = language;
        this.tlumaczenia = tlumaczenia;
        if (this.preload) {
            mainExecutor.submit(() -> {
                LOGGER.debug("Zaczynam pobieranie stron...");
                ExecutorService executor = Executors.newFixedThreadPool(2, new NamedThreadFactory("PageLoader-" +
                        userId + "-" + messageId + "-" + pages.size() + "-pages"));
                pages.forEach(executor::execute);
                while (!pages.stream().allMatch(FutureTask::isDone)) {
                    try {
                        if (ended) {
                            pages.forEach(f -> f.cancel(true));
                            break;
                        }
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                loaded();
                executor.shutdownNow();
                LOGGER.debug("Gotowe!");
            });
        } else loading = false;
    }

    @Override
    public void create(MessageChannel channel, String referenceMessageId) {
        try {
            MessageAction action = channel.sendMessage(render(1));
            if (referenceMessageId != null) action = action.referenceById(referenceMessageId);
            action = addReactions(action);
            action.override(true).queue(msg -> {
                message = msg;
                messageId = msg.getIdLong();
                if (pages.size() != 1) {
                    waitForReaction();
                }
            });
        } catch (LoadingException ignored) {
        }
    }

    @Override
    public void create(Message message) {
        this.message = message;
        messageId = message.getIdLong();
        eventBus.post(new PluginMessageEvent("core", PMSTO, PMZAADD + message.getId()));
        try {
            addReactions(message.editMessage(render(1))).override(true).queue(msg -> {
                if (pages.size() != 1) {
                    waitForReaction();
                }
            });
        } catch (LoadingException ignored) {
        }
    }

    private MessageAction addReactions(MessageAction action) {
        if (pages.size() == 1) return action;
        List<Button> secondRowButtons = new ArrayList<>();
        secondRowButtons.add(Button.danger("STOP_PAGE", Emoji.ofUnicode(STOP_EMOJI)));
        secondRowButtons.add(Button.secondary("CHOOSE_PAGE", Emoji.ofUnicode(ONETWOTHREEFOUR_EMOJI)));
        if (enableShuffle) secondRowButtons.add(Button.secondary("SHUFFLE_PAGE", Emoji.ofUnicode(SHUFFLE_EMOJI)));
        if (enableDelett) secondRowButtons.add(Button.danger("TRASH_PAGE", Emoji.ofUnicode(TRASH_EMOJI)));
        return action.setActionRows(
                ActionRow.of(
                        Button.secondary("FIRST_PAGE", Emoji.ofUnicode(FIRST_EMOJI)).withDisabled(pageNo == 1),
                        Button.primary("PREV_PAGE", Emoji.ofUnicode(LEFT_EMOJI))
                                .withLabel(String.valueOf(Math.max(pageNo - 1, 1))).withDisabled(pageNo == 1),
                        Button.primary("NEXT_PAGE", Emoji.ofUnicode(RIGHT_EMOJI))
                                .withLabel(String.valueOf(Math.min(pageNo + 1, pages.size()))).withDisabled(pageNo == pages.size()),
                        Button.secondary("LAST_PAGE", Emoji.ofUnicode(LAST_EMOJI)).withDisabled(pageNo == pages.size())
                ),
                ActionRow.of(secondRowButtons)
        );
    }

    private void waitForReaction() {
        eventWaiter.waitForEvent(ButtonClickEvent.class, this::checkReaction,
                this::handleReaction, timeout, TimeUnit.SECONDS, this::clearReactions);
    }

    private boolean checkReaction(ButtonClickEvent event) {
        if (event.getMessageIdLong() == messageId) {
            if (event.getUser().getIdLong() == userId) event.deferEdit().queue();
            else {
                event.reply(tlumaczenia.get(tlumaczenia.getLanguage(event.getMember()),
                        "paginator.invalid.user", "<@" + userId + ">")).setEphemeral(true).queue();
                return false;
            }
            switch (event.getComponentId()) {
                case "FIRST_PAGE":
                case "PREV_PAGE":
                case "NEXT_PAGE":
                case "LAST_PAGE":
                case "STOP_PAGE":
                case "CHOOSE_PAGE":
                    return true;
                case "SHUFFLE_PAGE":
                    return enableShuffle;
                case "TRASH_PAGE":
                    return enableDelett;
                default:
                    return false;
            }
        }
        return false;
    }

    private void handleReaction(ButtonClickEvent event) {
        final int oldPageNo = pageNo;
        switch (event.getComponentId()) {
            case "FIRST_PAGE":
                pageNo = 1;
                break;
            case "PREV_PAGE":
                if (pageNo > 1) pageNo--;
                break;
            case "NEXT_PAGE":
                if (pageNo < pages.size()) pageNo++;
                break;
            case "LAST_PAGE":
                pageNo = pages.size();
                break;
            case "STOP_PAGE":
                clearReactions();
                return;
            case "CHOOSE_PAGE":
                doKtorej = event.getChannel().sendMessage(tlumaczenia.get(language, "paginator.waiting.for.pageno")).complete();
                eventWaiter.waitForEvent(MessageReceivedEvent.class, this::checkMessage,
                        this::handleMessage, timeout, TimeUnit.SECONDS, this::clearReactions);
                return;
            case "SHUFFLE_PAGE":
                pageNo = ThreadLocalRandom.current().nextInt(pages.size()) + 1;
                break;
            case "TRASH_PAGE":
                clearReactions(true);
                return;
            default: return;
        }

        eventBus.post(new PluginMessageEvent("core", PMSTO, PMZAADD + message.getId()));
        try {
            addReactions(message.editMessage(render(pageNo))).override(true).queue(msg -> waitForReaction());
        } catch (LoadingException e) {
            pageNo = oldPageNo;
            addReactions(message.editMessage(render(pageNo))).override(true).queue(msg -> waitForReaction());
        }
    }

    private void handleMessage(MessageReceivedEvent event) {
        final int oldPageNo = pageNo;
        pageNo = Integer.parseInt(event.getMessage().getContentRaw());
        eventBus.post(new PluginMessageEvent("core", PMSTO, PMZAADD + doKtorej.getId()));
        doKtorej.delete().queue();
        doKtorej = null;
        eventBus.post(new PluginMessageEvent("core", PMSTO, PMZAADD + event.getMessage().getId()));
        event.getMessage().delete().queue();
        try {
            addReactions(message.editMessage(render(pageNo))).override(true).queue(msg -> waitForReaction());
        } catch (LoadingException e) {
            pageNo = oldPageNo;
            addReactions(message.editMessage(render(pageNo))).override(true).queue(msg -> waitForReaction());
        }
    }

    private boolean checkMessage(MessageReceivedEvent e) {
        try {
            return (Integer.parseInt(e.getMessage().getContentRaw()) >= 1 && Integer.parseInt(e.getMessage().getContentRaw()) <= pages.size()) &&
            e.isFromGuild() && e.getTextChannel().equals(message.getTextChannel())
                    && e.getAuthor().getIdLong() == userId;
        } catch (Exception ignored) {
            return false;
        }
    }

    private MessageEmbed render(int page) {
        FutureTask<EmbedBuilder> pageEmbed = pages.get(page - 1);
        EmbedBuilder eb;
        if (!pageEmbed.isDone()) mainExecutor.submit(pageEmbed);
        try {
            if (page == 1) {
                if (pageEmbed.get() == null) throw new IllegalStateException("pEmbed == null");
                eb = new EmbedBuilder(pageEmbed.get().build());
            }
            else {
                EmbedBuilder pEmbed = pageEmbed.get(5, TimeUnit.SECONDS);
                if (pEmbed == null) throw new IllegalStateException("pEmbed == null");
                eb = new EmbedBuilder(pEmbed.build());
            }
        } catch (TimeoutException e) {
            message.getChannel().sendMessage(tlumaczenia.get(language, "generic.lazyloading"))
                    .queue(m -> m.delete().queueAfter(5, TimeUnit.SECONDS));
            throw new LoadingException();
        } catch (ExecutionException e) {
            Sentry.getContext().setUser(new io.sentry.event.User(String.valueOf(userId), null,
                    null, null));
            Sentry.capture(e);
            LOGGER.error("Ładowanie strony nawaliło", e);
            if (page == 1) {
                message.getChannel()
                        .sendMessage(tlumaczenia.get(language, "generic.dynamicembedpaginator.errorone")).queue();
                throw new LoadingException(true);
            }
            message.getChannel().sendMessage(tlumaczenia.get(language, "generic.dynamicembedpaginator.error"))
                    .queue(m -> m.delete().queueAfter(5, TimeUnit.SECONDS));
            Sentry.clearContext();
            throw new LoadingException();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (!customFooter) {
            eb.setFooter(String.format("%s/%s", page, pages.size()), null);
            if (loading) eb.setFooter(String.format("%s/%s", page, pages.size())
                    + " ⌛", null);
        }
        else {
            String stopka = Objects.requireNonNull(eb.build().getFooter(),
                    "stopka jest null mimo customFooter").getText();
            if (stopka == null) throw new NullPointerException("tekst stopki jest null mimo customFooter");
            eb.setFooter(String.format(stopka, page, pages.size()), null);
            //noinspection ConstantConditions (ustawiamy ją wyżej)
            stopka = eb.build().getFooter().getText();
            if (loading) //noinspection ConstantConditions (ustawiamy tekst wyżej)
                eb.setFooter(stopka.endsWith(" ⌛") ? stopka : stopka + " ⌛", null);
            else //noinspection ConstantConditions (ustawiamy tekst wyżej)
                eb.setFooter(stopka.endsWith(" ⌛") ? stopka.substring(0, stopka.length() - 2): stopka, null);
        }
        return eb.build();
    }

    private void clearReactions() {
        clearReactions(false);
    }

    private void clearReactions(boolean delett) {
        ended = true;
        try {
            if (doKtorej != null) {
                eventBus.post(new PluginMessageEvent("core", PMSTO, PMZAADD + doKtorej.getId()));
                doKtorej.delete().queue();
            }
            if (delett) {
                eventBus.post(new PluginMessageEvent("core", PMSTO, PMZAADD + message.getId()));
                message.delete().queue();
            } else message.editMessage(message.getContentRaw()).setActionRows(Collections.emptySet()).queue();
        } catch (PermissionException ignored) {/*lul*/}
    }

    @Override
    public DynamicEmbedPaginator setCustomFooter(boolean customFooter) {
        this.customFooter = customFooter;
        return this;
    }

    public DynamicEmbedPaginator setEnableShuffle(boolean enableShuffle) {
        this.enableShuffle = enableShuffle;
        return this;
    }

    public DynamicEmbedPaginator setEnableDelett(boolean enableDelett) {
        this.enableDelett = enableDelett;
        return this;
    }

    public DynamicEmbedPaginator setTimeout(long timeout) {
        this.timeout = timeout;
        return this;
    }

    private void loaded() {
        this.loading = false;
        long waitUntil = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        //noinspection StatementWithEmptyBody
        while (message == null && System.currentTimeMillis() < waitUntil); // czekamy aż będzie wiadomość, max 5s
        addReactions(message.editMessage(render(pageNo))).override(true).queue();
    }

    private static class LoadingException extends RuntimeException {
        @Getter private final boolean firstPage;

        LoadingException() {
            this(false);
        }

        LoadingException(boolean firstPage) {
            this.firstPage = firstPage;
        }
    }
}
