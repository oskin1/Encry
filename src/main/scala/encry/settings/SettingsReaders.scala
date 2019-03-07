package encry.settings

import java.io.File
import java.net.InetSocketAddress

import com.typesafe.config.Config
import encry.storage.VersionalStorage
import encry.storage.VersionalStorage.StorageType
import encry.utils.ByteStr
import net.ceedubs.ficus.readers.ValueReader

trait SettingsReaders {
  implicit val byteStrReader: ValueReader[ByteStr] = (cfg, path) => ByteStr.decodeBase58(cfg.getString(path)).get
  implicit val storageTypeReader: ValueReader[StorageType] = (cfg, path) =>
    cfg.getString(path) match {
      case "iodb" => VersionalStorage.IODB
      case "LevelDb" => VersionalStorage.LevelDB
    }
  implicit val fileReader: ValueReader[File] = (cfg, path) => new File(cfg.getString(path))
  implicit val byteValueReader: ValueReader[Byte] = (cfg, path) => cfg.getInt(path).toByte
  implicit val inetSocketAddressReader: ValueReader[InetSocketAddress] = { (config: Config, path: String) =>
    val split = config.getString(path).split(":")
    new InetSocketAddress(split(0), split(1).toInt)
  }
}