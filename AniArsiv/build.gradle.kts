import com.lagradost.cloudstream3.gradle.CloudstreamExtension

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

version = 6

cloudstream {
    authors = listOf("ulgenzade")
    language = "tr"
    description = "AniArşiv — Türkiye'nin En Büyük Anime İzleme Arşivi (6.000+ Anime, 70.000+ Bölüm, Ultra HD)"
    status = 1
    tvTypes = listOf("Anime")
}