package io.motohub.android.feature.trips

import io.motohub.android.feature.ridedashboard.nav.NavRoute
import java.time.Instant

internal fun TripDetails.toGpx(): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<gpx version=\"1.1\" creator=\"MOTO-HUB\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
    append("  <metadata><name>").append(xmlEscape(summary.name ?: "MOTO-HUB ride"))
        .append("</name></metadata>\n")
    append("  <trk><name>").append(xmlEscape(summary.name ?: "MOTO-HUB ride"))
        .append("</name><trkseg>\n")
    points.forEach { point ->
        append("    <trkpt lat=\"").append(point.latitude)
            .append("\" lon=\"").append(point.longitude).append("\">")
        point.altitudeMeters?.let { append("<ele>").append(it).append("</ele>") }
        append("<time>").append(Instant.ofEpochMilli(point.timestampMillis)).append("</time>")
        append("</trkpt>\n")
    }
    append("  </trkseg></trk>\n</gpx>\n")
}

internal fun NavRoute.toGpx(name: String = "MOTO-HUB route"): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<gpx version=\"1.1\" creator=\"MOTO-HUB\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
    append("  <metadata><name>").append(xmlEscape(name)).append("</name></metadata>\n")
    append("  <trk><name>").append(xmlEscape(name)).append("</name><trkseg>\n")
    points.forEach { point ->
        append("    <trkpt lat=\"").append(point.latitude)
            .append("\" lon=\"").append(point.longitude).append("\">")
        append("<time>").append(Instant.now()).append("</time>")
        append("</trkpt>\n")
    }
    append("  </trkseg></trk>\n</gpx>\n")
}

private fun xmlEscape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")
