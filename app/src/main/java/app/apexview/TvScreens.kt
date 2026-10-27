package app.apexview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * Signing in on the TV: a code on screen, and the phone does the typing. The phone app (signed
 * in already) finds the TV on the home network under Settings → Sign in a TV and sends its
 * token with the code. F1's sign-in page is still there as a fallback.
 */
@Composable
fun TvSignInScreen(app: AppState) {
    val pairing = app.tvPairing
    DisposableEffect(Unit) {
        pairing.start()
        onDispose { pairing.stop() }
    }
    val fallback = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { fallback.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Ink.background)) {
        AsyncImage(
            model = F1TvApi.picture(app.featuredWeekend()?.image, 1920, 1080),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.45f },
        )
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Ink.background, 0.7f to Ink.background.copy(alpha = 0.4f), 1f to Color.Transparent)))
        Column(
            Modifier.align(Alignment.CenterStart).padding(horizontal = 64.dp).widthIn(max = 620.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Apexview", style = Type.largeTitle)
            Text("Sign in from your phone", style = Type.title2)
            Text(
                "On your phone, open Apexview Fold, go to Settings → Sign in a TV, pick this TV and enter this code. Both need to be on the same Wi-Fi.",
                style = Type.body.copy(color = Ink.secondary),
            )
            Text(
                pairing.code.chunked(3).joinToString(" "),
                style = Type.largeTitle.copy(fontSize = 64.sp, lineHeight = 72.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.08.em),
                modifier = Modifier.padding(vertical = 8.dp),
            )
            pairing.address?.let {
                Text("If your phone doesn't find this TV, enter its address: $it", style = Type.subhead)
            }
            (pairing.status ?: app.message)?.let { Text(it, style = Type.footnote.copy(color = Ink.primary)) }
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                "Sign in on the TV instead",
                Modifier.focusRequester(fallback),
                onClick = app::startLogin,
            )
        }
    }
}

/** On the phone: find a TV that's showing a code, and sign it in with this phone's account. */
@Composable
fun SendToTvDialog(app: AppState) {
    val finder = app.tvFinder
    var chosen by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    // One TV on the network is the usual case: pick it.
    LaunchedEffect(finder.found.size) {
        if (chosen == null && finder.found.size == 1) chosen = finder.found.first().name
    }
    Dialog(
        onDismissRequest = app::closeSendToTv,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = 460.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Ink.surface)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Sign in a TV", style = Type.title2)
            Text(
                "Open Apexview on your TV. It shows a six-digit code. This phone's F1 TV sign-in is sent to the TV over your home Wi-Fi.",
                style = Type.subhead,
            )
            if (!typing) {
                if (finder.found.isEmpty()) {
                    Text(if (finder.searching) "Looking for TVs on this Wi-Fi…" else "No TVs found yet.", style = Type.footnote)
                }
                finder.found.forEach { tv ->
                    val picked = chosen == tv.name
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .pressable(scaleTo = 0.99f) { chosen = tv.name }
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (picked) Ink.surfaceHigh else Color.Transparent)
                            .heightIn(min = 50.dp)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Tv, null, tint = Ink.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(tv.name, style = Type.body, modifier = Modifier.weight(1f))
                        if (picked) {
                            Box(Modifier.size(22.dp).clip(CircleShape).background(Ink.primary), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                Text(
                    "Enter the TV's address instead",
                    style = Type.footnote,
                    modifier = Modifier.pressable { typing = true }.padding(vertical = 6.dp),
                )
            } else {
                Field(manual, { manual = it }, "Address shown on the TV, like 192.168.1.20:47820", KeyboardType.Uri)
                Text(
                    "Find TVs on this Wi-Fi instead",
                    style = Type.footnote,
                    modifier = Modifier.pressable { typing = false; manual = "" }.padding(vertical = 6.dp),
                )
            }
            Field(code, { code = it.filter(Char::isDigit).take(6) }, "Code on the TV", KeyboardType.NumberPassword)
            app.sendToTvResult?.let { Text(it, style = Type.footnote.copy(color = Ink.primary)) }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                SecondaryButton("Close", onClick = app::closeSendToTv)
                val ready = code.length == 6 && (if (typing) manual.isNotBlank() else chosen != null) && !app.sendToTvBusy
                PrimaryButton(if (app.sendToTvBusy) "Sending…" else "Sign in TV", enabled = ready) {
                    val target = finder.found.firstOrNull { it.name == chosen }
                    app.sendToTv(if (typing) null else target, if (typing) manual else null, code)
                }
            }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, type: KeyboardType) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(hint, style = Type.body.copy(color = Ink.tertiary)) },
        singleLine = true,
        textStyle = Type.body,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Done),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Ink.secondary,
            unfocusedBorderColor = Ink.surfaceHigh,
            cursorColor = Ink.primary,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
