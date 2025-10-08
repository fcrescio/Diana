package li.crescio.penates.diana.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import li.crescio.penates.diana.BuildConfig
import li.crescio.penates.diana.R
import li.crescio.penates.diana.ui.theme.DianaTheme

@Composable
fun SplashScreen(
    modifier: Modifier = Modifier,
    versionName: String = BuildConfig.VERSION_NAME,
) {
    val backgroundBrush = remember {
        Brush.radialGradient(
            colors = listOf(
                Color(0xFF100224),
                Color(0xFF1D0842),
                Color(0xFF2E1456),
            ),
            center = Offset.Zero,
            radius = 900f,
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .padding(horizontal = 32.dp, vertical = 48.dp),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize(),
            color = Color.Transparent,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val splashPainter = rememberVectorPainter(dianaHuntressVector)
                val description = stringResource(R.string.splash_diana_content_description)
                Image(
                    painter = splashPainter,
                    contentDescription = description,
                    modifier = Modifier
                        .width(260.dp)
                        .height(320.dp),
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Diana",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = Color(0xFFEFE7FF),
                        letterSpacing = 2.sp,
                    ),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Guardian of captured thoughts",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = Color(0xFFD1C5FF),
                        textAlign = TextAlign.Center,
                    ),
                )
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = "build v$versionName",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Color(0xFFB9AFFF),
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.5.sp,
                    ),
                )
            }
        }
    }
}

private val dianaHuntressVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "DianaHuntress",
        defaultWidth = 260.dp,
        defaultHeight = 320.dp,
        viewportWidth = 260f,
        viewportHeight = 320f,
    ).apply {
        path(
            fill = SolidColor(Color(0xFF261040)),
            pathFillType = PathFillType.NonZero,
        ) {
            addPathNodes("M130 16C64.486 16 12 68.486 12 134s52.486 118 118 118 118-52.486 118-118S195.514 16 130 16z")
        }
        path(
            fill = SolidColor(Color(0xFF3C1F6A)),
            pathFillType = PathFillType.NonZero,
        ) {
            addPathNodes("M182 32c43.96 18.88 74 64.44 74 108 0 64.88-43.84 123.32-108 145.6 40.96-44.72 49.04-110.16 27.6-164.16C165.2 86.84 154.6 62.96 182 32z")
        }
        path(
            fill = SolidColor(Color(0xFFF3CEA0)),
            pathFillType = PathFillType.NonZero,
        ) {
            addPathNodes("M144 58a24 24 0 1 1 48 0 24 24 0 1 1-48 0z")
        }
        path(
            fill = SolidColor(Color(0xFF4A2A86)),
            pathFillType = PathFillType.NonZero,
        ) {
            addPathNodes("M148 90c25.84 31.56 41.28 72.44 38.12 112.64-3.16 40.08-22.04 78.8-52.12 107.36-20.96-24.48-32.52-50.6-33.96-78.24-1.44-27.4 6.28-55.72 20.92-85.04l-32.36 10.08C101.52 124.72 117.8 94.72 148 90z")
        }
        path(
            fill = SolidColor(Color(0xFF6A3FB4)),
            pathFillType = PathFillType.NonZero,
        ) {
            addPathNodes("M128 128c-25.2 27.28-36.76 59.12-32.4 92 3.64 25.76 16.28 49.28 36 69.6-44.16-14.08-75.4-51.04-80.4-88.96C46.44 154.16 74.04 130.16 128 128z")
        }
        path(
            fill = SolidColor(Color(0xFFBFA1FF)),
            stroke = SolidColor(Color(0xFFBFA1FF)),
            strokeLineWidth = 10f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathFillType = PathFillType.NonZero,
        ) {
            addPathNodes("M52 148c43.2-79.2 164.4-84 216 24-45.6 113.2-172.8 112.4-216-24z")
        }
        path(
            fill = SolidColor(Color(0xFFF5D680)),
            stroke = SolidColor(Color(0xFFF5D680)),
            strokeLineWidth = 6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathFillType = PathFillType.NonZero,
        ) {
            addPathNodes("M94 210l118-28")
        }
        path(
            fill = SolidColor(Color(0xFFF5D680)),
            pathFillType = PathFillType.NonZero,
        ) {
            addPathNodes("M214 176l18 14-24 8z")
        }
        path(
            fill = SolidColor(Color(0xFF201138)),
            pathFillType = PathFillType.NonZero,
        ) {
            addPathNodes("M168 82c10.32 4.4 19.72 10.72 26.96 18.8 4.16 4.52 7.44 9.24 9.2 14.44 1.36 4.12 1.52 8.64.48 13.56l-20.28-6.36c1.8-8.12.72-15.16-3.24-21.04-4.12-6.12-10.56-10.28-18.36-12.32z")
        }
    }.build()
}

@Preview(name = "Splash screen", showBackground = true, backgroundColor = 0xFF1A1038)
@Composable
private fun SplashScreenPreview() {
    DianaTheme {
        SplashScreen(versionName = "0.1")
    }
}
