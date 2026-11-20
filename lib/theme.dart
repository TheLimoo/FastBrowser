// Layered dark theme — the v1.0.5 palette, kept verbatim for v2.0.0.
// Near-black blue-charcoal background, clearly lighter elevated surfaces,
// visible borders, emerald accent. AMOLED mode: true-black background
// with surfaces that stay visibly lighter.

import 'package:flutter/material.dart';

class AppColors {
  static const emerald = Color(0xFF00E676);
  static const emeraldDark = Color(0xFF00B248);
  static const surfaceActive = Color(0xFF122019);
  static const amoledBlack = Color(0xFF000000);
  static const appBackground = Color(0xFF0A0C10);
  static const slateCard = Color(0xFF15171F);
  static const slateCardElevated = Color(0xFF1F222C);
  static const slateBorder = Color(0xFF2E3342);
  static const textPrimary = Color(0xFFF2F3F5);
  static const textSecondary = Color(0xFFA6ACB7);
  static const dangerRed = Color(0xFFFF5252);
  static const warningAmber = Color(0xFFFFC107);
}

ThemeData buildTheme({required bool amoledBlack}) {
  final background =
      amoledBlack ? AppColors.amoledBlack : AppColors.appBackground;
  final surface =
      amoledBlack ? const Color(0xFF101218) : AppColors.slateCard;
  final surfaceVariant =
      amoledBlack ? const Color(0xFF1A1D26) : AppColors.slateCardElevated;
  final outline =
      amoledBlack ? const Color(0xFF272C3A) : AppColors.slateBorder;
  final colorScheme = ColorScheme.dark(
    primary: AppColors.emerald,
    onPrimary: Colors.black,
    secondary: AppColors.emeraldDark,
    surface: surface,
    surfaceContainerHighest: surfaceVariant,
    onSurface: AppColors.textPrimary,
    onSurfaceVariant: AppColors.textSecondary,
    outline: outline,
    error: AppColors.dangerRed,
  );
  return ThemeData(
    colorScheme: colorScheme,
    scaffoldBackgroundColor: background,
    useMaterial3: true,
    appBarTheme: AppBarTheme(
      backgroundColor: surface,
      foregroundColor: AppColors.textPrimary,
      elevation: 0,
    ),
    cardTheme: CardThemeData(
      color: surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: outline),
      ),
    ),
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith(
        (s) => s.contains(WidgetState.selected)
            ? Colors.black
            : AppColors.textSecondary,
      ),
      trackColor: WidgetStateProperty.resolveWith(
        (s) => s.contains(WidgetState.selected)
            ? AppColors.emerald
            : surfaceVariant,
      ),
    ),
    sliderTheme: SliderThemeData(
      activeTrackColor: AppColors.emerald,
      thumbColor: AppColors.emerald,
      inactiveTrackColor: surfaceVariant,
    ),
    progressIndicatorTheme: const ProgressIndicatorThemeData(
      color: AppColors.emerald,
    ),
    floatingActionButtonTheme: const FloatingActionButtonThemeData(
      backgroundColor: AppColors.emerald,
      foregroundColor: Colors.black,
    ),
    textTheme: const TextTheme(
      titleLarge: TextStyle(fontWeight: FontWeight.w600, fontSize: 20),
      titleMedium: TextStyle(fontWeight: FontWeight.w600, fontSize: 16),
      bodyLarge: TextStyle(fontSize: 16),
      bodyMedium: TextStyle(fontSize: 14),
      bodySmall: TextStyle(fontSize: 12),
      labelLarge: TextStyle(fontWeight: FontWeight.w500, fontSize: 14),
      labelSmall: TextStyle(fontWeight: FontWeight.w500, fontSize: 11),
    ),
  );
}
