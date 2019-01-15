package encry.api.http.routes

import com.github.swagger.akka.model.{Info, License}
import com.github.swagger.akka.{SwaggerHttpService, model}
import encry.api.http.{PeersApiRoute, UtilsApiRoute}

object SwaggerRoute extends SwaggerHttpService {
  override def apiClasses: Set[Class[_]] =
    Set(classOf[HistoryApiRoute],
      classOf[InfoApiRoute],
      classOf[StateInfoApiRoute],
      classOf[TransactionsApiRoute],
      classOf[WalletInfoApiRoute],
      classOf[PeersApiRoute],
      classOf[UtilsApiRoute]
    )

  override def info: model.Info = Info(
    version = "0.9.2",
    title = "Encry Node API",
    description = "API docs for Encry Node.",
    license = Some(License(name = "GPL 3.0", url = "https://www.gnu.org/licenses/gpl-3.0.en.html"))
  )
}