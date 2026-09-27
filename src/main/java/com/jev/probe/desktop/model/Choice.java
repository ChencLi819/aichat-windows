package com.jev.probe.desktop.model;

import java.util.Map;

public record Choice(String choice, double confidence, Map<String, Double> probabilities) {
}
