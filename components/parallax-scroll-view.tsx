import type { PropsWithChildren, ReactElement } from "react";
import { ScrollView, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { useColors } from "@/hooks/use-colors";

const HEADER_HEIGHT = 250;

type Props = PropsWithChildren<{
  headerImage: ReactElement;
  headerBackgroundColor?: string;
}>;

/**
 * A scroll view with parallax header effect.
 * Note: Animated components require style objects for dynamic animations.
 */
export default function ParallaxScrollView({
  children,
  headerImage,
  headerBackgroundColor,
}: Props) {
  const colors = useColors();
  const insets = useSafeAreaInsets();
  const headerHeight = HEADER_HEIGHT + insets.top;

  return (
    <ScrollView
      style={{ backgroundColor: colors.background, flex: 1 }}
      contentContainerStyle={{
        paddingBottom: insets.bottom,
        paddingLeft: insets.left,
        paddingRight: insets.right,
      }}
    >
      <View
        style={{
          overflow: "hidden",
          backgroundColor: headerBackgroundColor ?? colors.primary,
          height: headerHeight,
          paddingTop: insets.top,
        }}
      >
        {headerImage}
      </View>
      <View className="flex-1 p-8 gap-4 overflow-hidden bg-background">
        {children}
      </View>
    </ScrollView>
  );
}
