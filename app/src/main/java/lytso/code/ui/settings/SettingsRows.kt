package lytso.code.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lytso.code.theme.RainbowAccent
import lytso.code.theme.ThemeManager
import lytso.code.ui.components.bouncyVerticalScroll

/**
 * One row of a settings list: an icon in a soft tinted badge, title, optional short subtitle,
 * optional status pill/value on the right, and a chevron.
 */
class SettingsRowItem(
    val icon: ImageVector,
    val tint: Color,
    val title: String,
    val value: String? = null,
    val valueColor: Color? = null,
    val subtitle: String? = null,
    val onClick: () -> Unit
)

/** A named section of settings rows shown under a clear uppercase section header. */
class SettingsSectionGroup(
    val title: String,
    val items: List<SettingsRowItem>
)

/**
 * Modern categorized settings page with optional top header card, instant search filter,
 * and clearly labeled rounded section cards.
 */
@Composable
fun SettingsSectionList(
    sections: List<SettingsSectionGroup>,
    cardBg: Color,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    searchable: Boolean = true,
    header: (@Composable () -> Unit)? = null
) {
    var query by remember { mutableStateOf("") }
    val trimmed = query.trim()
    val filteredSections = remember(sections, trimmed) {
        if (trimmed.isEmpty()) {
            sections.filter { it.items.isNotEmpty() }
        } else {
            sections.mapNotNull { section ->
                val matched = section.items.filter { item ->
                    item.title.contains(trimmed, ignoreCase = true) ||
                        (item.subtitle?.contains(trimmed, ignoreCase = true) == true) ||
                        (item.value?.contains(trimmed, ignoreCase = true) == true) ||
                        section.title.contains(trimmed, ignoreCase = true)
                }
                if (matched.isEmpty()) null else SettingsSectionGroup(section.title, matched)
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .bouncyVerticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (searchable) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = {
                    Text(
                        "Search settings…",
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Clear search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                } else null,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = cardBg,
                    unfocusedContainerColor = cardBg,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                ),
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (trimmed.isEmpty() && header != null) {
            header()
        }

        if (filteredSections.isEmpty() && trimmed.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = cardBg,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "No settings match \"$trimmed\"",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Try searching for themes, prompts, agents, terminal or backup.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            filteredSections.forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = section.title.uppercase(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.9.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp, top = 2.dp)
                    )
                    SettingsRowGroup(section.items, cardBg)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** A page of settings rows: rounded groups (without section headers); empty groups are skipped. */
@Composable
fun SettingsRowList(
    groups: List<List<SettingsRowItem>>,
    cardBg: Color,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState()
) {
    Column(
        modifier
            .fillMaxSize()
            .bouncyVerticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        groups.filter { it.isNotEmpty() }.forEach { SettingsRowGroup(it, cardBg) }
    }
}

/** Rows in one rounded card, split by thin dividers that start after the icon. */
@Composable
fun SettingsRowGroup(items: List<SettingsRowItem>, cardBg: Color) {
    val shape = RoundedCornerShape(18.dp)
    val isRainbow = ThemeManager.isRainbowAmoled
    val border = if (isRainbow) {
        RainbowAccent.borderStroke(width = 1.dp, alpha = 0.24f)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cardBg)
            .border(border, shape)
    ) {
        items.forEachIndexed { i, item ->
            SettingsRow(item)
            if (i < items.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 62.dp, end = 12.dp),
                    thickness = 0.6.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(item: SettingsRowItem) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = item.onClick)
            .heightIn(min = 58.dp)
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(item.tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(item.icon, null, tint = item.tint, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!item.subtitle.isNullOrBlank()) {
                Text(
                    item.subtitle,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!item.value.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        (item.valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.12f)
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                if (item.valueColor != null) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(item.valueColor))
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    item.value,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = onSurface.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp)
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null,
            tint = muted.copy(alpha = 0.7f),
            modifier = Modifier
                .padding(start = 4.dp)
                .size(19.dp)
        )
    }
}
