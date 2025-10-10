package li.crescio.penates.diana.ui

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
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
            val context = LocalContext.current
            val imageLoader = remember(context) {
                ImageLoader.Builder(context)
                    .components {
                        add(SvgDecoder.Factory())
                    }
                    .build()
            }
            val splashImageRequest = remember(context) {
                ImageRequest.Builder(context)
                    .data(R.raw.diana_huntress)
                    .build()
            }

            Column(
                modifier = Modifier
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val description = stringResource(R.string.splash_diana_content_description)
                AsyncImage(
                    model = splashImageRequest,
                    imageLoader = imageLoader,
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

@Preview(name = "Splash screen", showBackground = true, backgroundColor = 0xFF1A1038)
@Composable
private fun SplashScreenPreview() {
    DianaTheme {
        SplashScreen(versionName = "0.1")
    }
}
