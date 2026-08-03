                    MotionEvent.ACTION_UP -> {
                        val elapsed = System.currentTimeMillis() - touchStartTime
                        if (!hasMoved) {
                            when {
                                elapsed > 350 -> {
                                    onLongPress()
                                    SupabaseClient.logGesture("long_press")
                                }
                                System.currentTimeMillis() - lastTapTime < 300 -> {
                                    onDoubleTap()
                                    SupabaseClient.logGesture("double_tap")
                                }
                                else -> {
                                    lastTapTime = System.currentTimeMillis()
                                    onTap()
                                    SupabaseClient.logGesture("tap")
                                }
                            }

                            val now = System.currentTimeMillis()
                            if (now - tapCountResetTime > 2000) {
                                tapCount = 0
                                tapCountResetTime = now
                            }
                            tapCount++
                            when (tapCount) {
                                5 -> {
                                    SupabaseClient.logGesture("combo_5")
                                    setExpression("deadpan")
                                    sayBubble("Enough.", "angry")
                                }
                                8 -> {
                                    SupabaseClient.logGesture("combo_8")
                                    setExpression("soft")
                                    sayBubble("...", "soft")
                                }
                                10 -> {
                                    SupabaseClient.logGesture("combo_10")
                                    setExpression("surprised")
                                    sayBubble("Bloody hell.", "angry")
                                }
                            }
                        }
                        true
                    }