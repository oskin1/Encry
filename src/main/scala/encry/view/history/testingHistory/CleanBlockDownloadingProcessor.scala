package encry.view.history.testingHistory

import encry.settings.EncryAppSettings

case class CleanBlockDownloadingProcessor(settings: EncryAppSettings, minimalBlockHeight: Int = Int.MaxValue) {

  def updateBestBlockHeight(height: Int): CleanBlockDownloadingProcessor =
    this.copy(minimalBlockHeight =
      if (minimalBlockHeight == Int.MaxValue)
        if (settings.node.blocksToKeep < 0) settings.constants.GenesisHeight
        else Math.max(settings.constants.GenesisHeight, height - settings.node.blocksToKeep + 1)
      else if (settings.node.blocksToKeep >= 0) Math.max(height - settings.node.blocksToKeep + 1, minimalBlockHeight)
      else settings.constants.GenesisHeight
    )
}