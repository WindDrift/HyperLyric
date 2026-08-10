package com.lidesheng.hyperlyric.root.mediacard

import com.lidesheng.hyperlyric.root.mediacard.island.IslandExpandedMediaAmbientFlowHooker
import com.lidesheng.hyperlyric.root.mediacard.notification.NotificationMediaAmbientFlowHooker
import com.lidesheng.hyperlyric.root.mediacard.notification.switcher.NotificationMediaSingleCardSwitcherHooker

internal object MediaCardUiModeRefreshCoordinator {
    fun refresh() {
        val extraControllers =
            NotificationMediaSingleCardSwitcherHooker.extraCardControllers()
        // The original controller's foreground refresh fans out to cloned controllers.
        NotificationMediaAmbientFlowHooker.refreshForUiMode(extraControllers)
        IslandExpandedMediaAmbientFlowHooker.refreshForUiMode()
    }
}
