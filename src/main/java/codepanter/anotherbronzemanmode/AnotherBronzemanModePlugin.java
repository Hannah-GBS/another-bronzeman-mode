/*
 * Copyright (c) 2019, CodePanter <https://github.com/codepanter>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package codepanter.anotherbronzemanmode;

import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.Runnables;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.events.PluginChanged;
import net.runelite.api.ChatMessageType;
import net.runelite.api.widgets.*;
import net.runelite.client.RuneLite;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.client.Notifier;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.game.chatbox.ChatboxTextInput;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Text;
import net.runelite.client.game.WorldService;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatCommandManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.text.SimpleDateFormat;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Provides;

import static net.runelite.http.api.RuneLiteAPI.GSON;

@Slf4j
@PluginDescriptor(
        name = "Another Bronzeman Mode",
        description = "Limits access to buying an item on the Grand Exchange until it is obtained otherwise.",
        tags = {"overlay", "bronzeman"}
)
public class AnotherBronzemanModePlugin extends Plugin
{
    static final String CONFIG_GROUP = "anotherbronzemanmode";
    private static final String BM_UNLOCKS_STRING = "!bmunlocks";
    private static final String BM_COUNT_STRING = "!bmcount";
    private static final String BM_RESET_STRING = "!bmreset";
    private static final String BM_BACKUP_STRING = "!bmbackup";

    final int COLLECTION_LOG_GROUP_ID = 621;
    final int COLLECTION_VIEW = 35;
    final int COLLECTION_VIEW_SCROLLBAR = 36;
    final int COLLECTION_VIEW_HEADER = 19;

    final int MENU_INSPECT = 2;
    final int MENU_DELETE = 3;

    private static final int GE_SEARCH_BUILD_SCRIPT = 751;

    static final Set<Integer> OWNED_INVENTORY_IDS = ImmutableSet.of(
            0,    // Reward from fishing trawler.
            93,   // Standard player inventory.
            94,   // Equipment inventory.
            95,   // Bank inventory.
            141,  // Barrows reward chest inventory.
            390,  // Kingdom Of Miscellania reward inventory.
            581,  // Chambers of Xeric chest inventory.
            612,  // Theater of Blood reward chest inventory (Raids 2).
            626); // Seed vault located inside the Farming Guild.

    @Inject
    private Client client;

    @Inject
    private ChatboxPanelManager chatboxPanelManager;

    @Inject
    private Notifier notifier;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ItemManager itemManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private WorldService worldService;

    @Inject
    private ChatMessageManager chatMessageManager;

    @Inject
    private ChatCommandManager chatCommandManager;

    @Inject
    private AnotherBronzemanModeConfig config;

    @Inject
    private AnotherBronzemanModeOverlay AnotherBronzemanModeOverlay;

    private List<Integer> unlockedItems;

    @Getter
    private BufferedImage unlockImage = null;

    private static final String SCRIPT_EVENT_SET_CHATBOX_INPUT = "setChatboxInput";
    private static final String IRONMAN_PREFIX = "<img=" + IconID.IRONMAN.getIndex() + ">";

    private ChatboxTextInput searchInput;
    private Widget searchButton;
    private Collection<Widget> itemEntries;

    private List<String> namesBronzeman = new ArrayList<>();
    private int bronzemanIconOffset = -1; // offset for bronzeman icon
    private boolean onLeagueWorld;
    private boolean deleteConfirmed = false;
    private File playerFile;
    private File playerFolder;

    @Provides
    AnotherBronzemanModeConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(AnotherBronzemanModeConfig.class);
    }

    @Override
    protected void startUp() throws Exception
    {
        super.startUp();
        onLeagueWorld = false;
        updateNamesBronzeman();
        updateScreenshotUnlock();
        loadResources();
        unlockedItems = new ArrayList<>();
        overlayManager.add(AnotherBronzemanModeOverlay);
        chatCommandManager.registerCommand(BM_UNLOCKS_STRING, this::OnUnlocksCountCommand);
        chatCommandManager.registerCommand(BM_COUNT_STRING, this::OnUnlocksCountCommand);
        chatCommandManager.registerCommand(BM_BACKUP_STRING, this::OnUnlocksBackupCommand);

        if (config.resetCommand())
        {
            chatCommandManager.registerCommand(BM_RESET_STRING, this::OnUnlocksResetCommand);
        }

        clientThread.invoke(() ->
        {
            if (client.getGameState() == GameState.LOGGED_IN)
            {
                onLeagueWorld = isLeagueWorld(client.getWorld());
                // A player can not be a bronze man on a league world.
                if (!onLeagueWorld)
                {
                    setChatboxName(getNameChatbox());
                }
            }
        });
    }

    @Override
    protected void shutDown() throws Exception
    {
        super.shutDown();
        itemEntries = null;
        unlockedItems = null;
        overlayManager.remove(AnotherBronzemanModeOverlay);
        chatCommandManager.unregisterCommand(BM_UNLOCKS_STRING);
        chatCommandManager.unregisterCommand(BM_COUNT_STRING);
        chatCommandManager.unregisterCommand(BM_BACKUP_STRING);
        if (config.resetCommand())
        {
            chatCommandManager.unregisterCommand(BM_RESET_STRING);
        }

        clientThread.invoke(() ->
        {
            // Cleanup is not required after having played on a league world.
            if (client.getGameState() == GameState.LOGGED_IN && !onLeagueWorld)
            {
                setChatboxName(getNameDefault());
            }
        });
    }

    /** Loads players unlocks on login **/
    @Subscribe
    public void onGameStateChanged(GameStateChanged e)
    {
        if (e.getGameState() == GameState.LOGGED_IN)
        {
            setupPlayerFile();
            loadPlayerUnlocks();
            loadResources();
            onLeagueWorld = isLeagueWorld(client.getWorld());
        }
        if (e.getGameState() == GameState.LOGIN_SCREEN)
        {
            itemEntries = null;
        }
    }

    @Subscribe
    public void onPluginChanged(PluginChanged e)
    {
        if (e.getPlugin() == this && client.getGameState() == GameState.LOGGED_IN)
        {
            setupPlayerFile();
            loadPlayerUnlocks();
        }
    }

    /** Unlocks all new items that are currently not unlocked **/
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged e)
    {
        if (OWNED_INVENTORY_IDS.contains(e.getContainerId()))
        {
            unlockItemContainerItems(e.getItemContainer());
        }
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event)
    {
        if (event.getScriptId() == GE_SEARCH_BUILD_SCRIPT)
        {
            killSearchResults();
        }
    }

    @Subscribe
    public void onScriptCallbackEvent(ScriptCallbackEvent scriptCallbackEvent)
    {
        if (scriptCallbackEvent.getEventName().equals(SCRIPT_EVENT_SET_CHATBOX_INPUT) && !onLeagueWorld)
        {
            setChatboxName(getNameChatbox());
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage chatMessage)
    {
        if (client.getGameState() != GameState.LOADING && client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        String name = Text.removeTags(chatMessage.getName());
        switch (chatMessage.getType())
        {
            case PRIVATECHAT:
            case MODPRIVATECHAT:
                // Note this is unable to change icon on PMs if they are not a friend or in friends chat
            case FRIENDSCHAT:
                if (isChatPlayerOnNormalWorld(name) && isChatPlayerBronzeman(name))
                {
                    addBronzemanIconToMessage(chatMessage);
                }
                break;
            case PUBLICCHAT:
            case MODCHAT:
                if (!onLeagueWorld && isChatPlayerBronzeman(name))
                {
                    addBronzemanIconToMessage(chatMessage);
                }
                break;
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (event.getGroup().equals(CONFIG_GROUP))
        {
            if (event.getKey().equals("namesBronzeman"))
            {
                updateNamesBronzeman();
            }
            else if (event.getKey().equals("screenshotUnlock") || event.getKey().equals("includeFrame"))
            {
                updateScreenshotUnlock();
            }
            else if (event.getKey().equals("resetCommand"))
            {
                if (config.resetCommand())
                {
                    chatCommandManager.registerCommand(BM_RESET_STRING, this::OnUnlocksResetCommand);
                }
                else
                {
                    chatCommandManager.unregisterCommand(BM_RESET_STRING);
                }
            }

        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        if (event.getGroupId() != COLLECTION_LOG_GROUP_ID) {
            return;
        }
        itemEntries = null;
        clientThread.invokeLater(() -> {
            Widget collectionViewHeader = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW_HEADER);
            Widget[] headerComponents = collectionViewHeader.getDynamicChildren();
            headerComponents[0].setText("Bronze Man Unlocks");
            headerComponents[1].setText("Unlocks: <col=ff0000>" + Integer.toString(unlockedItems.size()));
            if (headerComponents.length > 2) {
                headerComponents[2].setText("");
            }
            createSearchButton(collectionViewHeader);

            Widget collectionView = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW);
            Widget scrollbar = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW_SCROLLBAR);
            collectionView.deleteAllChildren();

            int index = 0;
            int x = 0;
            int y = 0;
            int yIncrement = 40;
            int xIncrement = 42;
            for (Integer itemId : unlockedItems) {
                addItemToCollectionLog(collectionView, itemId, x, y, index);
                x = x + xIncrement;
                index++;
                if (x > 210) {
                    x = 0;
                    y = y + yIncrement;
                }
            }

            collectionView.setScrollHeight(y + 3);
            int scrollHeight = (collectionView.getScrollY() * y) / collectionView.getScrollHeight();
            collectionView.revalidateScroll();
            client.runScript(ScriptID.UPDATE_SCROLLBAR, scrollbar.getId(), collectionView.getId(), scrollHeight);
            collectionView.setScrollY(0);
            scrollbar.setScrollY(0);
        });

    }

    private void createSearchButton(Widget header) {
        searchButton = header.createChild(-1, WidgetType.GRAPHIC);
        searchButton.setSpriteId(SpriteID.GE_SEARCH);
        searchButton.setOriginalWidth(18);
        searchButton.setOriginalHeight(17);
        searchButton.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT);
        searchButton.setOriginalX(5);
        searchButton.setOriginalY(20);
        searchButton.setHasListener(true);
        searchButton.setAction(1, "Open");
        searchButton.setOnOpListener((JavaScriptCallback) e -> openSearch());
        searchButton.setName("Search");
        searchButton.revalidate();
    }

    private void openSearch() {
        updateFilter("");
        client.playSoundEffect(SoundEffectID.UI_BOOP);
        searchButton.setAction(1, "Close");
        searchButton.setOnOpListener((JavaScriptCallback) e -> closeSearch());
        searchInput = chatboxPanelManager.openTextInput("Search unlock list")
                .onChanged(s -> clientThread.invokeLater(() -> updateFilter(s.trim())))
                .onClose(() ->
                {
                    clientThread.invokeLater(() -> updateFilter(""));
                    searchButton.setOnOpListener((JavaScriptCallback) e -> openSearch());
                    searchButton.setAction(1, "Open");
                })
                .build();
    }

    private void closeSearch()
    {
        updateFilter("");
        chatboxPanelManager.close();
        client.playSoundEffect(SoundEffectID.UI_BOOP);
    }

    private void updateFilter(String input)
    {
        final Widget collectionView = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW);

        if (collectionView == null)
        {
            return;
        }

        String filter = input.toLowerCase();
        updateList(collectionView, filter);
    }

    private void updateList(Widget collectionView, String filter)
    {

        if (itemEntries == null)
        {
            itemEntries = Arrays.stream(collectionView.getDynamicChildren())
                    .sorted(Comparator.comparing(Widget::getRelativeY))
                    .collect(Collectors.toList());
        }

        itemEntries.forEach(w -> w.setHidden(true));

        Collection<Widget> matchingItems = itemEntries.stream()
                .filter(w -> w.getName().toLowerCase().contains(filter))
                .collect(Collectors.toList());

        int x = 0;
        int y = 0;
        for (Widget entry : matchingItems)
        {
            entry.setHidden(false);
            entry.setOriginalY(y);
            entry.setOriginalX(x);
            entry.revalidate();
            x = x + 42;
            if (x > 210) {
                x = 0;
                y = y + 40;
            }
        }

        y += 3;

        int newHeight = 0;

        if (collectionView.getScrollHeight() > 0)
        {
            newHeight = (collectionView.getScrollY() * y) / collectionView.getScrollHeight();
        }

        collectionView.setScrollHeight(y);
        collectionView.revalidateScroll();

        Widget scrollbar = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_VIEW_SCROLLBAR);
        client.runScript(
                ScriptID.UPDATE_SCROLLBAR,
                scrollbar.getId(),
                collectionView.getId(),
                newHeight
        );
    }

    private void addItemToCollectionLog(Widget collectionView, Integer itemId, int x, int y, int index) {
        String itemName = itemManager.getItemComposition(itemId).getName();
        Widget newItem = collectionView.createChild(index, 5);
        newItem.setContentType(0);
        newItem.setItemId(itemId);
        newItem.setItemQuantity(1);
        newItem.setItemQuantityMode(0);
        newItem.setModelId(-1);
        newItem.setModelType(1);
        newItem.setSpriteId(-1);
        newItem.setBorderType(1);
        newItem.setFilled(false);
        newItem.setOriginalX(x);
        newItem.setOriginalY(y);
        newItem.setOriginalWidth(36);
        newItem.setOriginalHeight(32);
        newItem.setHasListener(true);
        newItem.setAction(1, "Inspect");
        newItem.setAction(2, "Remove");
        newItem.setOnOpListener((JavaScriptCallback) e -> handleItemAction(itemId, itemName, e));
        newItem.setName(itemName);
        newItem.revalidate();
    }

    private void handleItemAction(Integer itemId, String itemName, ScriptEvent event) {
        switch (event.getOp()) {
            case MENU_INSPECT:
                final ChatMessageBuilder examination = new ChatMessageBuilder()
                        .append(ChatColorType.NORMAL)
                        .append("This is an unlocked item called '" + itemName + "'.");

                chatMessageManager.queue(QueuedMessage.builder()
                        .type(ChatMessageType.ITEM_EXAMINE)
                        .runeLiteFormattedMessage(examination.build())
                        .build());
                break;
            case MENU_DELETE:
                clientThread.invokeLater(() -> confirmDeleteItem(itemId, itemName));
                break;
        }
    }

    private void confirmDeleteItem(Integer itemId, String itemName)
    {
        chatboxPanelManager.openTextMenuInput("Do you want to re-lock: " + itemName)
                .option("1. Confirm re-locking of item", () ->
                        clientThread.invoke(() ->
                        {
                            deleteConfirmed = true;
                            queueItemDelete(itemId);
                            sendChatMessage("Item '" + itemName + "' is no longer unlocked.");
                            deleteConfirmed = false;
                        })
                )
                .option("2. Cancel", Runnables::doNothing)
                .build();
    }


    /** Unlocks all items in the given item container. **/
    public void unlockItemContainerItems(ItemContainer itemContainer)
    {
        for (Item i : itemContainer.getItems())
        {
            int itemId = i.getId();
            int realItemId = itemManager.canonicalize(itemId);
            ItemComposition itemComposition = itemManager.getItemComposition(itemId);
            int noteId = itemComposition.getNote();
            if (itemId != realItemId && noteId != 799) continue;  // The 799 signifies that it is a noted item
            if (i.getId() <= 1) continue;
            if (i.getQuantity() <= 0) continue;
            if (!unlockedItems.contains(realItemId))
            {
                queueItemUnlock(realItemId);
                if (config.sendNotification())
                {
                    notifier.notify("You have unlocked a new item: " + client.getItemDefinition(realItemId).getName() + ".");
                }
                else if (config.sendChatMessage())
                {
                    sendChatMessage("You have unlocked a new item: " + client.getItemDefinition(realItemId).getName() + ".");
                }
            }
        }
    }

    /** Queues a new unlock to be properly displayed **/
    public void queueItemUnlock(int itemId)
    {
        unlockedItems.add(itemId);
        AnotherBronzemanModeOverlay.addItemUnlock(itemId);
        savePlayerUnlocks();// Save after every item to fail-safe logging out
    }

    /** Queues the removal of an unlocked item **/
    public void queueItemDelete(int itemId)
    {
        sendChatMessage("before " + Integer.toString(unlockedItems.size()));
        unlockedItems.remove(Integer.valueOf(itemId));
        savePlayerUnlocks(); // Save after every item to fail-safe logging out
        sendChatMessage("after " + Integer.toString(unlockedItems.size()));
    }

    /** Unlocks default items like a bond to a newly made profile **/
    private void unlockDefaultItems()
    {
        queueItemUnlock(ItemID.COINS_995);
        queueItemUnlock(ItemID.OLD_SCHOOL_BOND);
    }

    private void sendChatMessage(String chatMessage)
    {
        final String message = new ChatMessageBuilder()
            .append(ChatColorType.HIGHLIGHT)
            .append(chatMessage)
            .build();

        chatMessageManager.queue(
            QueuedMessage.builder()
                .type(ChatMessageType.CONSOLE)
                .runeLiteFormattedMessage(message)
                .build());
    }

    void killSearchResults()
    {
        Widget grandExchangeSearchResults = client.getWidget(162, 53);

        if (grandExchangeSearchResults == null)
        {
            return;
        }

        Widget[] children = grandExchangeSearchResults.getDynamicChildren();

        if (children == null || children.length < 2 || children.length % 3 != 0)
        {
            return;
        }

        for (int i = 0; i < children.length; i+= 3) {
            if (!unlockedItems.contains(children[i + 2].getItemId()))
            {
                children[i].setHidden(true);
                children[i + 1].setOpacity(70);
                children[i + 2].setOpacity(70);
            }
        }
    }

    /* Saves players unlock JSON to a .txt file every time they unlock a new item */
    private void savePlayerUnlocks()
    {
        try
        {
            PrintWriter w = new PrintWriter(playerFile);
            String json = GSON.toJson(unlockedItems);
            w.println(json);
            w.close();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    /* Loads a players unlock JSON everytime they login */
    private void loadPlayerUnlocks()
    {
        unlockedItems.clear();
        try
        {
            String json = new Scanner(playerFile).useDelimiter("\\Z").next();
            unlockedItems.addAll(GSON.fromJson(json, new TypeToken<List<Integer>>(){}.getType()));
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private void updateNamesBronzeman()
    {
        namesBronzeman = Text.fromCSV(config.namesBronzeman());
    }

    private void updateScreenshotUnlock()
    {
        boolean screenshotUnlock = config.screenshotUnlock();
        boolean includeFrame = config.includeFrame();
        AnotherBronzemanModeOverlay.updateScreenshotUnlock(screenshotUnlock, includeFrame);
    }

    /**
     * Adds the Bronze man Icon in front of player names.
     *
     * @param chatMessage chat message to edit sender name on
     */
    private void addBronzemanIconToMessage(ChatMessage chatMessage)
    {
        String name = chatMessage.getName();
        if (!name.equals(Text.removeTags(name)))
        {
            // If the name has any tags, no bronze man icon will be added.
            // This makes it so Iron men can't be flagged as bronze man, but
            // currently also excludes mods.
            return;
        }

        final MessageNode messageNode = chatMessage.getMessageNode();
        messageNode.setName(getNameWithIcon(bronzemanIconOffset, name));

        chatMessageManager.update(messageNode);
        client.refreshChat();
    }

    /**
     * Checks if the world is a League world.
     *
     * @param worldNumber number of the world to check.
     * @return boolean true/false if it is a league world or not.
     */
    private boolean isLeagueWorld(int worldNumber)
    {
        WorldResult worlds = worldService.getWorlds();
        if (worlds == null)
        {
            return false;
        }

        World world = worlds.findWorld(worldNumber);
        return world != null && world.getTypes().contains(WorldType.LEAGUE);
    }

    /**
     * Checks if the given message was sent by the player
     *
     * @param chatMessage number of the world to check.
     * @return boolean true/false if the message was sent by the player.
     */
    private boolean sentByPlayer(ChatMessage chatMessage)
    {
        MessageNode messageNode = chatMessage.getMessageNode();

        return Text.sanitize(messageNode.getName()).equals(Text.sanitize(client.getLocalPlayer().getName()));
    }

    /**
     * Update the player name in the chatbox input
     */
    private void setChatboxName(String name)
    {
        Widget chatboxInput = client.getWidget(WidgetInfo.CHATBOX_INPUT);
        if (chatboxInput != null)
        {
            String text = chatboxInput.getText();
            int idx = text.indexOf(':');
            if (idx != -1)
            {
                String newText = name + text.substring(idx);
                chatboxInput.setText(newText);
            }
        }
    }

    /**
     * Gets the bronzeman name, including possible icon, of the local player.
     *
     * @return String of icon + name
     */
    private String getNameChatbox()
    {
        Player player = client.getLocalPlayer();
        if (player != null)
        {
            return getNameWithIcon(bronzemanIconOffset, player.getName());
        }
        return null;
    }

    /**
     * Gets the default name, including possible icon, of the local player.
     *
     * @return String of icon + name
     */
    private String getNameDefault()
    {
        Player player = client.getLocalPlayer();
        if (player == null)
        {
            return null;
        }

        int iconIndex;
        switch (client.getAccountType())
        {
            case IRONMAN:
                iconIndex = IconID.IRONMAN.getIndex();
                break;
            case HARDCORE_IRONMAN:
                iconIndex = IconID.HARDCORE_IRONMAN.getIndex();
                break;
            case ULTIMATE_IRONMAN:
                iconIndex = IconID.ULTIMATE_IRONMAN.getIndex();
                break;
            default:
                return player.getName();
        }

        return getNameWithIcon(iconIndex, player.getName());
    }

    /**
     * Get a name formatted with icon
     *
     * @param iconIndex index of the icon
     * @param name      name of the player
     * @return String of icon + name
     */
    private static String getNameWithIcon(int iconIndex, String name)
    {
        String icon = "<img=" + iconIndex + ">";
        return icon + name;
    }

    /**
     * Gets a ChatPlayer object from a clean name by searching friends chat and friends list.
     *
     * @param name name of player to find.
     * @return ChatPlayer if found, else null.
     */
    private ChatPlayer getChatPlayerFromName(String name)
    {
        // Search friends chat members first, because if a friend is in the friends chat but their private
        // chat is 'off', then we won't know the world
        FriendsChatManager friendsChatManager = client.getFriendsChatManager();
        if (friendsChatManager != null)
        {
            FriendsChatMember friendsChatMember = friendsChatManager.findByName(name);
            if (friendsChatMember != null)
            {
                return friendsChatMember;
            }
        }

        NameableContainer<Friend> friendContainer = client.getFriendContainer();
        return friendContainer.findByName(name);
    }

    /**
     * Checks if a player name is a friend or friends chat member is a bronzeman.
     *
     * @param name name of player to check.
     * @return boolean true/false.
     */
    private boolean isChatPlayerBronzeman(String name)
    {
        return isChatPlayerOnNormalWorld(name) && (namesBronzeman.contains(name) || namesBronzeman.contains(name.replace('\u00A0', ' ')));
    }

    /**
     * Checks if a player name is a friend or friends chat member on a normal world.
     *
     * @param name name of player to check.
     * @return boolean true/false.
     */
    private boolean isChatPlayerOnNormalWorld(String name)
    {
        ChatPlayer player = getChatPlayerFromName(name);

        if (player == null)
        {
            return true;
        }

        int world = player.getWorld();
        return !isLeagueWorld(world);
    }

    /**
     * Sets up the playerFile variable, and makes the player file if needed.
     */
    private void setupPlayerFile()
    {
        playerFolder = new File(RuneLite.PROFILES_DIR, client.getUsername());
        if (!playerFolder.exists())
        {
            playerFolder.mkdirs();
        }
        playerFile = new File(playerFolder, "another-bronzeman-mode-unlocks.txt");
        if (!playerFile.exists())
        {
            try {
                playerFile.createNewFile();
                unlockDefaultItems();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void OnUnlocksCountCommand(ChatMessage chatMessage, String message)
    {
        if (!sentByPlayer(chatMessage))
        {
            return;
        }

        final ChatMessageBuilder builder = new ChatMessageBuilder()
            .append(ChatColorType.HIGHLIGHT)
            .append("You have unlocked ")
            .append(ChatColorType.NORMAL)
            .append(Integer.toString(unlockedItems.size()))
            .append(ChatColorType.HIGHLIGHT)
            .append(" items.");

        String response = builder.build();

        MessageNode messageNode = chatMessage.getMessageNode();
        messageNode.setRuneLiteFormatMessage(response);
        chatMessageManager.update(messageNode);
        client.refreshChat();
    }

    private void OnUnlocksResetCommand(ChatMessage chatMessage, String message)
    {
        if (!sentByPlayer(chatMessage))
        {
            return;
        }
        resetItemUnlocks();
        sendChatMessage("Unlocks succesfully reset!");
    }

    private void resetItemUnlocks(){
        try {
            playerFile.delete();
            unlockedItems.clear();
            savePlayerUnlocks();
            unlockDefaultItems();
            unlockItemContainerItems(client.getItemContainer(InventoryID.INVENTORY));
            unlockItemContainerItems(client.getItemContainer(InventoryID.EQUIPMENT));
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
    }

    private void OnUnlocksBackupCommand(ChatMessage chatMessage, String message)
    {
        if (!sentByPlayer(chatMessage))
        {
            return;
        }
        backupItemUnlocks();
        sendChatMessage("Successfully backed up file!");
    }

    private void backupItemUnlocks()
    {
        Path originalPath = playerFile.toPath();
        try {
            Calendar cal = Calendar.getInstance();
            SimpleDateFormat sdf = new SimpleDateFormat("MM_WW_HH_mm_ss");
            Files.copy(originalPath, Paths.get(playerFolder.getPath() + "_" + sdf.format(cal.getTime()) + ".backup"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
    }

    /**
     * Loads the bronzeman resources into the client.
     */
    private void loadResources()
    {
        final IndexedSprite[] modIcons = client.getModIcons();

        if (bronzemanIconOffset != -1 || modIcons == null)
        {
            return;
        }

        unlockImage = ImageUtil.getResourceStreamFromClass(getClass(), "/item-unlocked.png");
        BufferedImage image = ImageUtil.getResourceStreamFromClass(getClass(), "/bronzeman_icon.png");
        IndexedSprite indexedSprite = ImageUtil.getImageIndexedSprite(image, client);

        bronzemanIconOffset = modIcons.length;

        final IndexedSprite[] newModIcons = Arrays.copyOf(modIcons, modIcons.length + 1);
        newModIcons[newModIcons.length - 1] = indexedSprite;

        client.setModIcons(newModIcons);
    }
}
