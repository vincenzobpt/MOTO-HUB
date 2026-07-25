package io.motohub.android.feature.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.motohub.android.feature.ridedashboard.nav.NavRoute
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.i18n.motoHubText
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.units.UnitFormat

@Composable
fun M2bWeatherCard(route: NavRoute) {
    route.weatherAtArrival?.let { weather ->
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        motoHubText("Weather at arrival"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        motoHubText("%1\$s · %2\$d°C", weather.description, weather.temperatureCelsius.toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun M2bFuelWarning(route: NavRoute) {
    val units = MotoHubSettings.distanceUnits(LocalContext.current)
    route.estimatedFuelRemainingKm?.let { remaining ->
        // The 50 km alert threshold stays metric so behavior is unit-independent.
        if (remaining < 50) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (remaining < 20)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            motoHubText(if (remaining < 20) "Low fuel warning" else "Fuel check"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            motoHubText(
                                "%1\$d ${UnitFormat.wholeDistanceLabel(units)} remaining after this route",
                                UnitFormat.wholeDistanceFromKm(remaining, units)
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun M2bGoldenHourCard(route: NavRoute) {
    route.minutesToGoldenHour?.takeIf { it <= GOLDEN_HOUR_RELEVANCE_MINUTES }?.let { minutes ->
        val icon = if (minutes == 0) "🌅" else "⏰"
        val text = if (minutes == 0) motoHubText("Golden hour now") else motoHubText("Golden hour in %1\$d min", minutes)

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(icon, style = MaterialTheme.typography.headlineSmall)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

private const val GOLDEN_HOUR_RELEVANCE_MINUTES = 180

@Composable
fun M2bCurvyInfo(route: NavRoute) {
    if (route.curvedSegments.isNotEmpty()) {
        val curviestSegment = route.curvedSegments.maxByOrNull { it.averageCurviness }
        curviestSegment?.let { segment ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            motoHubText("Curvy roads ahead"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            motoHubText("%1\$d curved segments detected", route.curvedSegments.size),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}
