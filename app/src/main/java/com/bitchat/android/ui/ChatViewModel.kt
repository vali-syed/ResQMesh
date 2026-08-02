package com.bitchat.android.ui

import android.app.Application
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.favorites.FavoritesChangeListener
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.features.voice.LiveVoicePreferences
import com.bitchat.android.features.voice.LiveVoiceTarget
import com.bitchat.android.features.voice.VoiceRecorder
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.*
import com.bitchat.android.net.NetworkConnectivityObserver
import com.bitchat.android.nostr.*
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.services.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Date
import kotlin.random.Random

/**
 * Live identity state within a single conversation context (the ChatScreen or its components).
 */
data class ConversationLiveIdentityState(
    val connectedPeerIDs: List<String>,
    val peerNicknames: Map<String, String>,
    val persistedDisplayNames: Map<String, String>
)

/**
 * Refactored ChatViewModel - Main coordinator for bitchat functionality
 * Delegates specific responsibilities to specialized managers while maintaining 100% iOS compatibility
 */
class ChatViewModel(
    application: Application,
    initialMeshService: BluetoothMeshService,
    initialUnifiedMeshService: MeshService
) : AndroidViewModel(application), BluetoothMeshDelegate {

    // Made var to support mesh service replacement after panic clear
    var meshService: BluetoothMeshService = initialMeshService
        private set
    private var unifiedMeshService: MeshService = initialUnifiedMeshService
    private val mesh: MeshService
        get() = unifiedMeshService
    private val debugManager by lazy { try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }

    private val connectivityObserver = NetworkConnectivityObserver(application)
    val internetStatus = connectivityObserver.status.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = NetworkConnectivityObserver.Status.Offline
    )

    companion object {
        private const val TAG = "ChatViewModel"
        private const val CONVERSATION_DISCONNECT_GRACE_MS = 3_000L
    }

    fun sendVoiceNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        mediaSendingManager.sendVoiceNote(toPeerIDOrNull, channelOrNull, filePath)
    }

    fun createVoiceRecorder(toPeerIDOrNull: String?, channelOrNull: String?): VoiceRecorder {
        val context = getApplication<Application>().applicationContext
        if (!LiveVoicePreferences.isEnabled(context)) return VoiceRecorder(context)
        val recipientPeerID = toPeerIDOrNull?.let {
            PrivateMediaRecipientResolver.resolve(it, mesh)?.meshPeerID
        }
        val liveTarget = when {
            toPeerIDOrNull != null && recipientPeerID != null && mesh.hasEstablishedSession(recipientPeerID) ->
                LiveVoiceTarget { payload -> mesh.sendVoiceFrame(recipientPeerID, payload) }
            toPeerIDOrNull == null && channelOrNull == null && mesh.getActivePeerCount() > 0 ->
                LiveVoiceTarget { payload -> mesh.sendVoiceFrame(null, payload) }
            else -> null
        }
        return VoiceRecorder(context, liveTarget)
    }

    fun sendFileNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        mediaSendingManager.sendFileNote(toPeerIDOrNull, channelOrNull, filePath)
    }

    fun sendImageNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        mediaSendingManager.sendImageNote(toPeerIDOrNull, channelOrNull, filePath)
    }

    fun approveLegacyPrivateMedia(requestId: String) {
        mediaSendingManager.approveLegacyPrivateMedia(requestId)
    }

    fun cancelLegacyPrivateMedia(requestId: String) {
        mediaSendingManager.cancelLegacyPrivateMedia(requestId)
    }

    fun getCurrentNpub(): String? {
        return try {
            NostrIdentityBridge
                .getCurrentNostrIdentity(getApplication())
                ?.npub
        } catch (_: Exception) {
            null
        }
    }

    fun buildMyQRString(nickname: String, npub: String?): String {
        return VerificationService.buildMyQRString(nickname, npub) ?: ""
    }

    // MARK: - State management
    private val state = ChatState(
        scope = viewModelScope,
    )

    // Transfer progress tracking
    private val transferMessageMap = mutableMapOf<String, String>()
    private val messageTransferMap = mutableMapOf<String, String>()

    // Specialized managers
    private val dataManager = DataManager(application.applicationContext)
    private val identityManager by lazy { SecureIdentityStateManager(getApplication()) }
    private val seenMessageStore by lazy {
        com.bitchat.android.services.SeenMessageStore.getInstance(getApplication())
    }
    private val conversationListPreferences =
        com.bitchat.android.services.ConversationListPreferences.getInstance(getApplication())
    private val messageManager = MessageManager(state)
    private val channelManager = ChannelManager(state, messageManager, dataManager, viewModelScope)

    // Create Noise session delegate for clean dependency injection
    private val noiseSessionDelegate = object : NoiseSessionDelegate {
        override fun hasEstablishedSession(peerID: String): Boolean = hasEstablishedSessionOnAnyLocalTransport(peerID)
        override fun initiateHandshake(peerID: String) = initiateNoiseHandshakeOnBestLocalTransport(peerID)
        override fun getMyPeerID(): String = mesh.myPeerID
    }

    val privateChatManager = PrivateChatManager(
        state = state,
        messageManager = messageManager,
        dataManager = dataManager,
        noiseSessionDelegate = noiseSessionDelegate,
        hasReadReceiptBeenSent = { messageID ->
            seenMessageStore.hasReadReceiptBeenSent(messageID)
        },
        markMessageReadLocally = { messageID ->
            messageManager.updateMessageDeliveryStatus(messageID, DeliveryStatus.Read(mesh.myPeerID, Date()))
        }
    )

    private val commandProcessor = CommandProcessor(
        state = state,
        messageManager = messageManager,
        channelManager = channelManager,
        privateChatManager = privateChatManager,
        coroutineScope = viewModelScope
    )

    private val notificationManager = NotificationManager(
        context = getApplication(),
        notificationManager = NotificationManagerCompat.from(getApplication())
    )

    private val verificationHandler = VerificationHandler(
        context = getApplication(),
        scope = viewModelScope,
        getMeshService = { mesh },
        identityManager = identityManager,
        state = state,
        notificationManager = notificationManager,
        messageManager = messageManager
    )

    val verifiedFingerprints: StateFlow<Set<String>> = verificationHandler.verifiedFingerprints

    private val mediaSendingManager = MediaSendingManager(
        state = state,
        messageManager = messageManager,
        channelManager = channelManager,
        scope = viewModelScope,
        mediaWorkDispatcher = Dispatchers.IO,
        getMeshService = { mesh }
    )

    private val meshDelegateHandler = MeshDelegateHandler(
        state = state,
        messageManager = messageManager,
        channelManager = channelManager,
        privateChatManager = privateChatManager,
        notificationManager = notificationManager,
        coroutineScope = viewModelScope,
        onHapticFeedback = { /* No-op */ },
        getMyPeerID = { mesh.myPeerID },
        getMeshService = { mesh },
        markMessageReadLocally = { messageID ->
            messageManager.updateMessageDeliveryStatus(messageID, DeliveryStatus.Read(mesh.myPeerID, Date()))
        }
    )

    internal val geohashViewModel = GeohashViewModel(
        application = getApplication(),
        state = state,
        messageManager = messageManager,
        dataManager = dataManager,
        notificationManager = notificationManager
    )

    // Exposed state for UI
    val messages: StateFlow<List<BitchatMessage>> = state.messages
    val connectedPeers: StateFlow<List<String>> = state.connectedPeers
    val nickname: StateFlow<String> = state.nickname
    val isConnected: StateFlow<Boolean> = state.isConnected
    val privateChats: StateFlow<Map<String, List<BitchatMessage>>> = state.privateChats
    val selectedPrivateChatPeer: StateFlow<String?> = state.selectedPrivateChatPeer
    val unreadPrivateMessages: StateFlow<Set<String>> = state.unreadPrivateMessages
    val conversationStoreState: StateFlow<com.bitchat.android.services.ConversationStoreState> =
        com.bitchat.android.services.AppStateStore.conversationStoreState
    private val conversationPresencePeers = MutableStateFlow<List<String>>(emptyList())
    private val conversationPresenceRemovalJobs = mutableMapOf<String, Job>()
    private val conversationDirectoryRevision = MutableStateFlow(0L)
    private var favoriteRelationshipListenerRegistered = false
    private val favoriteRelationshipChangeListener = object : FavoritesChangeListener {
        override fun onFavoriteChanged(fingerprint: String) {
            refreshConversationDirectoryState()
        }

        override fun onAllCleared() {
            refreshConversationDirectoryState()
        }
    }

    private fun refreshConversationDirectoryState() {
        viewModelScope.launch {
            conversationDirectoryRevision.update { it + 1L }
        }
    }

    private val conversationLiveIdentityState = combine(
        state.connectedPeers,
        state.peerNicknames,
        conversationPresencePeers,
        conversationDirectoryRevision,
        AppStateStore.privateConversationDisplayNames
    ) { connectedPeerIDs, peerNicknames, _, _, persistedDisplayNames ->
        ConversationLiveIdentityState(
            connectedPeerIDs = connectedPeerIDs,
            peerNicknames = peerNicknames,
            persistedDisplayNames = persistedDisplayNames
                .mapKeys { (conversationID, _) -> conversationID.lowercase() }
        )
    }

    private val baseConversations = combine(
        state.unreadPrivateMessages,
        state.privateChats,
        state.nickname,
        conversationLiveIdentityState,
        AppStateStore.unreadPrivateMessageCounts
    ) { unreadConversationIDs, chats, currentNickname, liveIdentity, unreadCounts ->
        val connectedPeerByIdentity = buildMap {
            liveIdentity.connectedPeerIDs.forEach { peerID ->
                val identities = runCatching {
                    val info = mesh.getPeerInfo(peerID)
                    val noiseHex = info?.noisePublicKey?.toHexString()?.lowercase()
                    val meshHex = peerID.lowercase()
                    listOfNotNull(noiseHex, meshHex)
                }.getOrDefault(listOf(peerID.lowercase()))
                identities.forEach { put(it, peerID) }
            }
        }

        chats.map { (conversationID, messages) ->
            val lastMessage = messages.lastOrNull()
            val isUnread = unreadConversationIDs.contains(conversationID)
            val unreadCount = unreadCounts[conversationID] ?: if (isUnread) 1 else 0
            
            val meshPeerID = connectedPeerByIdentity[conversationID.lowercase()]
            val persistedDisplayName = liveIdentity.persistedDisplayNames[
                conversationID.lowercase()
            ]

            val nickname = meshPeerID?.let { liveIdentity.peerNicknames[it] }
                ?: persistedDisplayName
                ?: conversationID
            
            ConversationSummary(
                conversationID = conversationID,
                displayName = nickname,
                unreadCount = unreadCount,
                latestMessageAt = lastMessage?.timestamp?.time ?: 0L,
                latestActivityOrder = lastMessage?.timestamp?.time ?: 0L,
                latestMessageType = lastMessage?.type ?: BitchatMessageType.Message,
                latestMessagePreview = lastMessage?.content ?: "",
                latestMessageIsOutgoing = lastMessage?.sender == currentNickname || lastMessage?.senderPeerID == mesh.myPeerID,
                latestDeliveryStatus = lastMessage?.deliveryStatus,
                transport = if (conversationID.startsWith("nostr_")) DirectMessageTransport.NOSTR else DirectMessageTransport.MESH,
                nostrPubkey = null,
                identityAliases = emptySet(),
                isConnected = meshPeerID != null,
                connectedPeerID = meshPeerID,
                isPinned = false,
                isMuted = false,
                draft = null
            )
        }.sortedByDescending { it.latestActivityOrder }
    }

    internal val conversations: StateFlow<List<ConversationSummary>> = combine(
        baseConversations,
        conversationListPreferences.pinned,
        conversationListPreferences.muted,
        conversationListPreferences.drafts
    ) { summaries, pinned, muted, drafts ->
        summaries.map { summary ->
            summary.copy(
                isPinned = pinned.contains(summary.conversationID),
                isMuted = muted.contains(summary.conversationID),
                draft = drafts[summary.conversationID]
            )
        }.sortedWith(compareByDescending<ConversationSummary> { it.isPinned }
            .thenByDescending { it.latestActivityOrder })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val joinedChannels: StateFlow<Set<String>> = state.joinedChannels
    val currentChannel: StateFlow<String?> = state.currentChannel
    val channelMessages: StateFlow<Map<String, List<BitchatMessage>>> = state.channelMessages
    val unreadChannelMessages: StateFlow<Map<String, Int>> = state.unreadChannelMessages
    val passwordProtectedChannels: StateFlow<Set<String>> = state.passwordProtectedChannels
    val showPasswordPrompt: StateFlow<Boolean> = state.showPasswordPrompt
    val passwordPromptChannel: StateFlow<String?> = state.passwordPromptChannel
    val hasUnreadChannels: StateFlow<Boolean> = state.hasUnreadChannels
    val hasUnreadPrivateMessages: StateFlow<Boolean> = state.hasUnreadPrivateMessages
    val showCommandSuggestions: StateFlow<Boolean> = state.showCommandSuggestions
    val commandSuggestions: StateFlow<List<CommandSuggestion>> = state.commandSuggestions
    val showMentionSuggestions: StateFlow<Boolean> = state.showMentionSuggestions
    val mentionSuggestions: StateFlow<List<String>> = state.mentionSuggestions
    val favoritePeers: StateFlow<Set<String>> = state.favoritePeers
    val peerFavoritedUs: StateFlow<Set<String>> = state.peerFavoritedUs
    val peerSessionStates: StateFlow<Map<String, String>> = state.peerSessionStates
    val peerFingerprints: StateFlow<Map<String, String>> = state.peerFingerprints
    val peerNicknames: StateFlow<Map<String, String>> = state.peerNicknames
    val peerRSSI: StateFlow<Map<String, Int>> = state.peerRSSI
    val peerDirect: StateFlow<Map<String, Boolean>> = state.peerDirect
    val showAppInfo: StateFlow<Boolean> = state.showAppInfo
    val showMeshPeerList: StateFlow<Boolean> = state.showMeshPeerList
    val privateChatSheetPeer: StateFlow<String?> = state.privateChatSheetPeer
    val showVerificationSheet: StateFlow<Boolean> = state.showVerificationSheet
    val showSecurityVerificationSheet: StateFlow<Boolean> = state.showSecurityVerificationSheet
    val legacyPrivateMediaConsent: StateFlow<LegacyPrivateMediaConsentRequest?> = mediaSendingManager.legacyPrivateMediaConsent
    val selectedLocationChannel: StateFlow<com.bitchat.android.geohash.ChannelID?> = state.selectedLocationChannel
    val isTeleported: StateFlow<Boolean> = state.isTeleported
    val geohashPeople: StateFlow<List<GeoPerson>> = geohashViewModel.geohashPeople
    val teleportedGeo: StateFlow<Set<String>> = state.teleportedGeo
    val geohashParticipantCounts: StateFlow<Map<String, Int>> = state.geohashParticipantCounts
    val meshServiceFacade: MeshService = mesh
    val myPeerID: String
        get() = mesh.myPeerID

    fun getMeshPeerFingerprint(peerID: String): String? = mesh.getPeerFingerprint(peerID)

    fun getMeshPeerInfo(peerID: String): com.bitchat.android.mesh.PeerInfo? = mesh.getPeerInfo(peerID)

    fun initiateMeshHandshake(peerID: String) {
        mesh.initiateNoiseHandshake(peerID)
    }

    fun getPeerNicknames(): Map<String, String> = mesh.getPeerNicknames()

    fun getConnectedPeerList(): List<String> = state.getConnectedPeersValue()

    fun handleBackPressed(): Boolean {
        // Close sheets if open
        if (state.showMeshPeerList.value) {
            state.setShowMeshPeerList(false)
            return true
        }
        if (state.showAppInfo.value) {
            state.setShowAppInfo(false)
            return true
        }
        if (state.privateChatSheetPeer.value != null) {
            state.setPrivateChatSheetPeer(null)
            return true
        }
        if (state.showVerificationSheet.value) {
            state.setShowVerificationSheet(false)
            return true
        }
        if (state.showSecurityVerificationSheet.value) {
            state.setShowSecurityVerificationSheet(false)
            return true
        }
        return false
    }

    // MARK: - App logic
    
    fun sendSOS(onAccepted: (Boolean) -> Unit = {}) {
        val locationManager = com.bitchat.android.geohash.LocationChannelManager.getInstance(getApplication())
        
        viewModelScope.launch {
            // 1. Force request a location fix (bypasses app privacy gate, respects system permission)
            locationManager.forceRequestOneShotLocation()
            
            // 2. Wait a bit for the fix to arrive (emergency tradeoff: speed vs accuracy)
            delay(1000)
            
            // 3. Try to get the most precise available geohash
            var currentGeohash = locationManager.availableChannels.value.firstOrNull()?.geohash
            
            // 4. Fallback to the currently selected channel if it's a location
            if (currentGeohash.isNullOrBlank()) {
                val selected = locationManager.selectedChannel.value
                if (selected is com.bitchat.android.geohash.ChannelID.Location) {
                    currentGeohash = selected.channel.geohash
                }
            }
            
            // 5. Try to get a human-readable city name
            val cityName = locationManager.locationNames.value[com.bitchat.android.geohash.GeohashChannelLevel.CITY]

            // 6. Create EmergencyPacket (as requested)
            val lastLoc = locationManager.lastLocation.value
            val emergencyPacket = EmergencyPacket(
                deviceId = mesh.myPeerID,
                timestamp = System.currentTimeMillis(),
                latitude = lastLoc?.latitude ?: 0.0,
                longitude = lastLoc?.longitude ?: 0.0,
                emergencyType = "SOS_BROADCAST",
                description = "Emergency Assistance Requested via ResQMesh SOS Button",
                status = "ACTIVE"
            )
            val emergencyJson = emergencyPacket.toJson()
            Log.d("ChatViewModel", "Created EmergencyPacket: $emergencyJson")
            
            // 7. Generate SOS message including machine-readable data
            val sosMessage = buildString {
                append("[SOS] EMERGENCY ASSISTANCE REQUESTED!")
                if (!cityName.isNullOrBlank()) {
                    append("\nCity: $cityName")
                }
                if (!currentGeohash.isNullOrBlank()) {
                    append("\nLocation: $currentGeohash")
                } else {
                    append("\nLocation: GPS Searching...")
                }
                // Append machine-readable packet for the Rescue Bridge / Backend
                append("\n[DATA]$emergencyJson")
            }
            
            // 8. Transmit through the existing pipeline
            sendMessage(sosMessage, onAccepted)
        }
    }

    fun sendMessage(
        content: String,
        onAccepted: (Boolean) -> Unit = {}
    ) {
        if (content.isEmpty()) {
            onAccepted(false)
            return
        }
        
        // Check for commands
        if (content.startsWith("/")) {
            val selectedLocationForCommand = state.selectedLocationChannel.value
            commandProcessor.processCommand(content, mesh, mesh.myPeerID, { messageContent, mentions, channel ->
                if (selectedLocationForCommand is com.bitchat.android.geohash.ChannelID.Location) {
                    // Route command-generated public messages via Nostr in geohash channels
                    geohashViewModel.sendGeohashMessage(
                        messageContent,
                        selectedLocationForCommand.channel,
                        mesh.myPeerID,
                        state.getNicknameValue()
                    )
                } else if (channel != null && channelManager.hasChannelKey(channel)) {
                    channelManager.sendEncryptedChannelMessage(
                        messageContent,
                        mentions,
                        channel,
                        state.getNicknameValue(),
                        mesh.myPeerID,
                        onEncryptedPayload = {
                            mesh.sendMessage(messageContent, mentions, channel)
                        },
                        onFallback = {
                            mesh.sendMessage(messageContent, mentions, channel)
                        }
                    )
                } else {
                    mesh.sendMessage(messageContent, mentions, channel)
                }
            }, this)
            onAccepted(true)
            return
        }
        
        val mentions = messageManager.parseMentions(content, mesh.getPeerNicknames().values.toSet(), state.getNicknameValue())
        var selectedPeer = state.getSelectedPrivateChatPeerValue()
        val currentChannelValue = state.getCurrentChannelValue()
        
        if (selectedPeer != null) {
            // If the selected peer is a temporary Nostr alias or a noise-hex identity, resolve to a canonical target
            selectedPeer = ContactDirectory.canonicalConversationId(
                com.bitchat.android.services.ConversationAliasResolver.resolveCanonicalPeerID(
                selectedPeerID = selectedPeer,
                connectedPeers = state.getConnectedPeersValue(),
                meshNoiseKeyForPeer = { pid -> mesh.getPeerInfo(pid)?.noisePublicKey },
                nostrPubHexForAlias = { alias -> com.bitchat.android.nostr.GeohashAliasRegistry.get(alias) },
                findNoiseKeyForNostr = { key -> com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(key) }
                )
            ).also { canonical ->
                if (canonical != state.getSelectedPrivateChatPeerValue()) {
                    privateChatManager.startPrivateChat(canonical, mesh)
                    // If we're in the private chat sheet, update its active peer too
                    if (state.getPrivateChatSheetPeerValue() != null) {
                        showPrivateChatSheet(canonical)
                    }
                }
            }
            // Send private message
            val recipientNickname = nicknameForPeer(selectedPeer)
            val destination = selectedPeer
            viewModelScope.launch {
                val accepted = privateChatManager.sendPrivateMessageDurably(
                    content,
                    destination,
                    recipientNickname,
                    state.getNicknameValue(),
                    mesh.myPeerID
                ) { messageContent, peerID, recipientNicknameParam, messageId ->
                    val router = com.bitchat.android.services.MessageRouter.getInstance(
                        getApplication(),
                        mesh
                    )
                    val route = router.sendPrivate(
                        messageContent,
                        peerID,
                        recipientNicknameParam,
                        messageId
                    )
                    if (route == com.bitchat.android.services.MessageRouter.RouteResult.NOSTR) {
                        messageManager.updateMessageDeliveryStatus(
                            messageId,
                            com.bitchat.android.model.DeliveryStatus.Sent
                        )
                    }
                }
                onAccepted(accepted)
            }
        } else {
            // Check if we're in a location channel
            val selectedLocationChannel = state.selectedLocationChannel.value
            if (selectedLocationChannel is com.bitchat.android.geohash.ChannelID.Location) {
                // Send to geohash channel via Nostr ephemeral event
                geohashViewModel.sendGeohashMessage(content, selectedLocationChannel.channel, mesh.myPeerID, state.getNicknameValue())
            } else {
                // Send public/channel message via mesh
                val message = BitchatMessage(
                    sender = state.getNicknameValue() ?: mesh.myPeerID,
                    content = content,
                    timestamp = Date(),
                    isRelay = false,
                    senderPeerID = mesh.myPeerID,
                    mentions = if (mentions.isNotEmpty()) mentions else null,
                    channel = currentChannelValue
                )

                if (currentChannelValue != null) {
                    channelManager.addChannelMessage(currentChannelValue, message, mesh.myPeerID)

                    // Check if encrypted channel
                    if (channelManager.hasChannelKey(currentChannelValue)) {
                        channelManager.sendEncryptedChannelMessage(
                            content,
                            mentions,
                            currentChannelValue,
                            state.getNicknameValue(),
                            mesh.myPeerID,
                            onEncryptedPayload = { encryptedData ->
                                mesh.sendMessage(content, mentions, currentChannelValue)
                            },
                            onFallback = {
                                mesh.sendMessage(content, mentions, currentChannelValue)
                            }
                        )
                    } else {
                        mesh.sendMessage(content, mentions, currentChannelValue)
                    }
                } else {
                    mesh.sendMessage(content, mentions, null)
                }
            }
            onAccepted(true)
        }
    }

    fun joinChannel(channel: String, password: String? = null): Boolean {
        return channelManager.joinChannel(channel, password, myPeerID = mesh.myPeerID)
    }

    fun leaveChannel(channel: String) {
        channelManager.leaveChannel(channel)
    }

    fun switchChannel(channel: String?) {
        state.setCurrentChannel(channel)
    }

    fun switchToChannel(channel: String?) {
        switchChannel(channel)
    }

    fun nicknameForPeer(peerID: String): String {
        return state.peerNicknames.value[peerID] ?: peerID
    }

    fun isPeerDirect(peerID: String): Boolean {
        return state.peerDirect.value[peerID] ?: false
    }

    fun hideAppInfo() {
        state.setShowAppInfo(false)
    }

    fun showAppInfo() {
        state.setShowAppInfo(true)
    }

    fun hideMeshPeerList() {
        state.setShowMeshPeerList(false)
    }

    fun showMeshPeerList() {
        state.setShowMeshPeerList(true)
    }

    fun hidePrivateChatSheet() {
        state.setPrivateChatSheetPeer(null)
    }

    fun showPrivateChatSheet(peerID: String, fromSidebar: Boolean = false) {
        state.setPrivateChatSheetPeer(peerID)
    }

    fun hideVerificationSheet() {
        state.setShowVerificationSheet(false)
    }

    fun showVerificationSheet() {
        state.setShowVerificationSheet(true)
    }

    fun hideSecurityVerificationSheet() {
        state.setShowSecurityVerificationSheet(false)
    }

    fun showSecurityVerificationSheet() {
        state.setShowSecurityVerificationSheet(true)
    }

    fun beginQRVerification(qr: VerificationService.VerificationQR): Boolean {
        return verificationHandler.beginQRVerification(qr)
    }

    fun unverifyFingerprint(peerID: String) {
        verificationHandler.unverifyFingerprint(peerID)
    }

    fun removeVerification(peerID: String) {
        verificationHandler.unverifyFingerprint(peerID)
    }

    fun selectLocationChannel(channel: com.bitchat.android.geohash.ChannelID) {
        geohashViewModel.selectLocationChannel(channel)
    }
    
    fun setCurrentGeohash(geohash: String) {
        notificationManager.setCurrentGeohash(geohash)
    }
    
    fun clearNotificationsForSender(peerID: String) {
        notificationManager.clearNotificationsForSender(peerID)
    }
    
    fun clearNotificationsForGeohash(geohash: String) {
        notificationManager.clearNotificationsForGeohash(geohash)
    }

    fun setNickname(newNickname: String) {
        dataManager.saveNickname(newNickname)
        state.setNickname(newNickname)
    }

    internal suspend fun deletePrivateConversation(conversationID: String): DeletedPrivateConversation? {
        val deletion = AppStateStore.deletePrivateConversationAndWait(conversationID)
        refreshConversationDirectoryState()
        return deletion
    }

    fun setConversationRead(conversationID: String, isRead: Boolean) {
        viewModelScope.launch {
            AppStateStore.setPrivateConversationRead(conversationID, isRead)
        }
    }

    fun conversationDraft(conversationID: String?): Flow<String?> {
        if (conversationID == null) return flowOf(null)
        return conversationListPreferences.drafts.map { it[conversationID] }
    }

    fun getDraft(conversationID: String?): String {
        if (conversationID == null) return ""
        return conversationListPreferences.draftFor(conversationID) ?: ""
    }

    internal suspend fun restoreDeletedConversation(deletion: DeletedPrivateConversation): Boolean {
        return AppStateStore.restoreDeletedConversation(deletion)
    }

    fun setDraft(conversationID: String, draft: String?) {
        if (draft == null) {
            conversationListPreferences.removeConversation(conversationID)
        } else {
            conversationListPreferences.setDraft(conversationID, draft)
        }
    }

    fun setConversationDraft(conversationID: String?, draft: String) {
        if (conversationID != null) {
            setDraft(conversationID, draft)
        }
    }

    fun toggleConversationPinned(conversationID: String) {
        conversationListPreferences.togglePinned(conversationID)
    }

    fun toggleConversationMuted(conversationID: String) {
        conversationListPreferences.toggleMuted(conversationID)
    }

    fun togglePinConversation(conversationID: String) {
        toggleConversationPinned(conversationID)
    }

    fun toggleMuteConversation(conversationID: String) {
        toggleConversationMuted(conversationID)
    }

    fun markConversationAsRead(conversationID: String) {
        viewModelScope.launch {
            AppStateStore.setPrivateConversationRead(conversationID, true)
        }
    }

    fun markConversationAsUnread(conversationID: String) {
        viewModelScope.launch {
            AppStateStore.setPrivateConversationRead(conversationID, false)
        }
    }

    override fun isFavorite(peerID: String): Boolean {
        return dataManager.isFavorite(peerID)
    }

    fun addFavorite(fingerprint: String) {
        dataManager.addFavorite(fingerprint)
        refreshConversationDirectoryState()
    }

    fun removeFavorite(fingerprint: String) {
        dataManager.removeFavorite(fingerprint)
        refreshConversationDirectoryState()
    }

    fun toggleFavorite(peerID: String) {
        privateChatManager.toggleFavorite(peerID)
    }

    fun isUserBlocked(fingerprint: String): Boolean {
        return dataManager.isUserBlocked(fingerprint)
    }

    fun blockUser(fingerprint: String) {
        dataManager.addBlockedUser(fingerprint)
    }

    fun unblockUser(fingerprint: String) {
        dataManager.removeBlockedUser(fingerprint)
    }

    fun isGeohashUserBlocked(pubkeyHex: String): Boolean {
        return dataManager.isGeohashUserBlocked(pubkeyHex)
    }

    fun blockUserInGeohash(pubkeyHex: String) {
        geohashViewModel.blockUserInGeohash(pubkeyHex)
    }

    fun unblockUserInGeohash(pubkeyHex: String) {
        dataManager.removeGeohashBlockedUser(pubkeyHex)
    }

    fun initiateNoiseHandshakeOnBestLocalTransport(peerID: String) {
        mesh.initiateNoiseHandshake(peerID)
    }

    fun hasEstablishedSessionOnAnyLocalTransport(peerID: String): Boolean {
        return mesh.hasEstablishedSession(peerID)
    }

    fun startPrivateChat(peerID: String) {
        privateChatManager.startPrivateChat(peerID, mesh)
    }

    fun endPrivateChat() {
        privateChatManager.endPrivateChat()
    }

    fun cancelMediaSend(transferId: String) {
        mesh.cancelFileTransfer(transferId)
    }

    fun updateCommandSuggestions(text: String) {
        commandProcessor.updateCommandSuggestions(text)
    }

    fun updateMentionSuggestions(text: String) {
        commandProcessor.updateMentionSuggestions(text, mesh, this)
    }

    fun selectCommandSuggestion(suggestion: CommandSuggestion): String {
        return commandProcessor.selectCommandSuggestion(suggestion)
    }

    fun selectMentionSuggestion(mention: String, currentText: String): String {
        return commandProcessor.selectMentionSuggestion(mention, currentText)
    }

    fun isPeerVerified(peerID: String, verifiedFingerprints: Set<String>? = null): Boolean {
        return verificationHandler.isPeerVerified(peerID)
    }

    fun isNoisePublicKeyVerified(noisePublicKey: ByteArray, verifiedFingerprints: Set<String>? = null): Boolean {
        return verificationHandler.isNoisePublicKeyVerified(noisePublicKey)
    }

    fun getPeerFingerprintForDisplay(peerID: String): String? {
        return verificationHandler.getPeerFingerprintForDisplay(peerID)
    }

    fun getMyFingerprint(): String {
        return verificationHandler.getMyFingerprint()
    }

    fun verifyFingerprintValue(fingerprint: String) {
        verificationHandler.verifyFingerprintValue(fingerprint)
    }

    fun unverifyFingerprintValue(fingerprint: String) {
        verificationHandler.unverifyFingerprintValue(fingerprint)
    }

    fun resolvePeerDisplayNameForFingerprint(fingerprint: String): String {
        return verificationHandler.resolvePeerDisplayNameForFingerprint(fingerprint)
    }

    fun peerIdentityForMeshPeer(peerID: String): PeerIdentity {
        return PeerIdentity.mesh(peerID)
    }

    fun startGeohashDM(pubkey: String, onResolved: (String) -> Unit = {}) {
        geohashViewModel.startGeohashDM(pubkey, onResolved)
    }

    fun startGeohashDMByShortId(shortId: String, onResolved: (String) -> Unit = {}) {
        geohashViewModel.startGeohashDMByShortId(shortId, onResolved)
    }

    fun startGeohashDMByNickname(nickname: String, onResolved: (String) -> Unit = {}) {
        geohashViewModel.startGeohashDMByNickname(nickname, onResolved)
    }

    fun peerIdentityForNostrPubkey(pubkey: String) = geohashViewModel.peerIdentityForNostrPubkey(pubkey)

    fun beginGeohashSampling(liveLocationGeohashes: Collection<String>, userSelectedGeohashes: Collection<String>) {
        geohashViewModel.beginGeohashSampling(liveLocationGeohashes, userSelectedGeohashes)
    }

    fun endGeohashSampling() {
        geohashViewModel.endGeohashSampling()
    }

    fun displayNameForGeohashConversation(fullPubkey: String, geohash: String): String {
        return geohashViewModel.displayNameForGeohashConversation(fullPubkey, geohash)
    }

    fun getPeerIDForNickname(nickname: String): String? {
        return mesh.getPeerNicknames().entries.find { it.value == nickname }?.key
    }

    fun panicClearAllData() {
        viewModelScope.launch {
            // 1. Force UI to close all sheets and go back to root
            state.setShowMeshPeerList(false)
            state.setShowAppInfo(false)
            state.setPrivateChatSheetPeer(null)
            state.setShowVerificationSheet(false)
            state.setShowSecurityVerificationSheet(false)
            
            // 2. Clear all in-memory and persisted state
            dataManager.clearAllData()
            AppStateStore.panicClearPrivateConversations()
            ConversationRepository.getInstance(getApplication()).clearAllAndWait()
            SeenMessageStore.getInstance(getApplication()).clear()
            
            // 3. Inform mesh layer (closes all connections and keys)
            unifiedMeshService.clearAllInternalData()
            
            // 4. Force a fresh mesh service reference for the next cycle
            // The existing reference is now invalidated/disconnected
            meshService = MeshServiceHolder.getOrCreate(getApplication())
            unifiedMeshService = MeshServiceHolder.getUnifiedOrCreate(getApplication())
            
            // 5. Update local state
            state.setNickname(dataManager.loadNickname())
            refreshConversationDirectoryState()
            
            Log.i(TAG, "Panic clear complete: all data wiped and mesh reset")
        }
    }

    override fun onCleared() {
        super.onCleared()
        // ViewModel is being destroyed, cleanup listeners
        if (favoriteRelationshipListenerRegistered) {
            com.bitchat.android.favorites.FavoritesPersistenceService.shared.removeListener(favoriteRelationshipChangeListener)
        }
    }

    // BluetoothMeshDelegate implementation - forwards to MeshDelegateHandler
    override fun didReceiveMessage(message: BitchatMessage) {
        meshDelegateHandler.didReceiveMessage(message)
    }

    override fun didUpdatePeerList(peers: List<String>) {
        meshDelegateHandler.didUpdatePeerList(peers)
    }

    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        meshDelegateHandler.didReceiveChannelLeave(channel, fromPeer)
    }

    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        meshDelegateHandler.didReceiveDeliveryAck(messageID, recipientPeerID)
    }

    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        meshDelegateHandler.didReceiveReadReceipt(messageID, recipientPeerID)
    }

    override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {
        meshDelegateHandler.didReceiveVerifyChallenge(peerID, payload, timestampMs)
    }

    override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {
        meshDelegateHandler.didReceiveVerifyResponse(peerID, payload, timestampMs)
    }

    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return meshDelegateHandler.decryptChannelMessage(encryptedContent, channel)
    }

    override fun getNickname(): String? {
        return meshDelegateHandler.getNickname()
    }
    
    // START - Open Latest Unread Private Chat
    // Feature used for deep linking or notification click if peerID is missing
    fun openLatestUnreadPrivateChat() {
        try {
            val unreadPeers = state.unreadPrivateMessages.value
            if (unreadPeers.isEmpty()) return
            
            // Find most recent unread
            val allChats = state.privateChats.value
            val openPeer = unreadPeers.maxByOrNull { peerID ->
                allChats[peerID]?.lastOrNull()?.timestamp?.time ?: 0L
            } ?: return
            
            showPrivateChatSheet(openPeer)
        } catch (e: Exception) {
            Log.w(TAG, "openLatestUnreadPrivateChat failed: ${e.message}")
        }
    }
}
