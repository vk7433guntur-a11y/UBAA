package cn.edu.ubaa.api

import io.ktor.http.Url

const val SIGNIN_MY_CENTER_URL = "https://iclass.buaa.edu.cn:8346/?type=jumpMyCenter"
const val SIGNIN_LOGIN_REDIRECT_LIMIT = 8

fun extractSigninLoginNameFromUrl(url: String): String? {
  val query = url.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
  if (query.isBlank()) return null
  return query.split('&').firstNotNullOfOrNull { part ->
    val key = part.substringBefore('=', missingDelimiterValue = part)
    if (!key.equals("loginName", ignoreCase = true)) return@firstNotNullOfOrNull null
    part.substringAfter('=', missingDelimiterValue = "").takeIf { it.isNotBlank() }?.percentDecode()
  }
}

private fun String.percentDecode(): String {
  if ('%' !in this) return this
  val output = StringBuilder(length)
  var index = 0
  while (index < length) {
    if (this[index] == '%' && index + 2 < length) {
      val value = substring(index + 1, index + 3).toIntOrNull(16)
      if (value != null) {
        output.append(value.toChar())
        index += 3
        continue
      }
    }
    output.append(this[index])
    index++
  }
  return output.toString()
}

fun resolveSigninRedirectUrl(currentUrl: String, location: String): String? {
  val target = location.trim()
  if (target.isBlank()) return null
  if (
      target.startsWith("http://", ignoreCase = true) ||
          target.startsWith("https://", ignoreCase = true)
  ) {
    return target
  }

  val base = runCatching { Url(currentUrl) }.getOrNull() ?: return null
  if (target.startsWith("//")) return "${base.protocol.name}:$target"

  val authority = buildString {
    append(base.protocol.name)
    append("://")
    append(base.host)
    if (base.specifiedPort > 0 && base.specifiedPort != base.protocol.defaultPort) {
      append(':')
      append(base.specifiedPort)
    }
  }
  val basePath = base.encodedPath.ifBlank { "/" }
  return when {
    target.startsWith("?") -> "$authority$basePath$target"
    target.startsWith("#") ->
        buildString {
          append(authority)
          append(basePath)
          base.encodedQuery
              .takeIf { it.isNotBlank() }
              ?.let {
                append('?')
                append(it)
              }
          append(target)
        }
    target.startsWith("/") -> "$authority$target"
    else -> {
      val directory = basePath.substringBeforeLast('/', missingDelimiterValue = "")
      val prefix = if (directory.isBlank()) "/" else "$directory/"
      "$authority$prefix$target"
    }
  }
}
