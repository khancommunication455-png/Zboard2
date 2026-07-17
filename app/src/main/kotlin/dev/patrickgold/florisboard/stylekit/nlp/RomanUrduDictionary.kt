/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.stylekit.nlp

/**
 * A small, hand-curated base dictionary of common Roman Urdu words (Urdu written in
 * Latin script, as typed casually in chat), each with a relative frequency weight
 * (higher = more common). This seeds [RomanUrduSuggestionProvider] so it has
 * something to correct against out of the box, without requiring a network download
 * or a bundled asset file.
 *
 * This list is intentionally starter-sized (a few hundred of the highest-frequency
 * everyday words/greetings/particles). It is meant to be grown over time — either by
 * expanding this list or, in a future iteration, by learning new Roman Urdu words the
 * same way [AdaptiveLearningProvider] learns English ones (a natural follow-up once
 * the language classifier is proven out).
 *
 * Word choice favors the *most common* spelling variant people actually type; where
 * more than one spelling is extremely common (e.g. "acha"/"acha"), both are included
 * so that neither looks like a "misspelling" of the other.
 */
object RomanUrduDictionary {

    /** word -> relative frequency (1..100) */
    val words: Map<String, Int> = buildMap {
        fun w(word: String, freq: Int) = put(word, freq)

        // Greetings / common expressions
        w("assalamualaikum", 60); w("walaikumassalam", 55); w("salam", 80)
        w("shukriya", 85); w("meherbani", 40); w("khuda", 60); w("allah", 90)
        w("inshallah", 85); w("mashallah", 70); w("subhanallah", 55)
        w("acha", 95); w("accha", 80); w("theek", 90); w("thik", 60)
        w("bohat", 75); w("bahut", 70); w("bohot", 55)

        // Pronouns / basic grammar
        w("mein", 95); w("main", 90); w("hum", 90); w("tum", 90); w("aap", 95)
        w("wo", 80); w("who", 40); w("yeh", 90); w("ye", 85); w("is", 60)
        w("us", 60); w("mera", 90); w("meri", 90); w("mere", 80)
        w("tera", 60); w("teri", 60); w("tere", 55); w("uska", 60); w("uski", 60)
        w("hamara", 55); w("hamari", 55); w("apna", 60); w("apni", 55)
        w("koi", 80); w("kuch", 85); w("sab", 80); w("sabhi", 40)

        // Verbs (very common)
        w("hai", 100); w("hain", 90); w("tha", 85); w("thi", 80); w("the", 60)
        w("hoga", 70); w("hogi", 60); w("hongay", 30); w("karna", 85)
        w("karo", 80); w("kiya", 85); w("kia", 70); w("karta", 60); w("karti", 60)
        w("kar", 90); w("krna", 55); w("krta", 40); w("krti", 40)
        w("raha", 70); w("rahi", 70); w("rahe", 60); w("hona", 60); w("hoon", 85)
        w("hun", 60); w("ho", 90); w("dena", 60); w("lena", 60); w("dekhna", 55)
        w("dekho", 65); w("suno", 65); w("bolna", 55); w("bol", 60); w("bolo", 55)
        w("jana", 70); w("jao", 75); w("aana", 60); w("aao", 60); w("aya", 60)
        w("ayi", 45); w("gaya", 60); w("gayi", 55); w("gaye", 45)
        w("chahiye", 75); w("chahta", 40); w("chahti", 40)
        w("pata", 70); w("pta", 50); w("malum", 40); w("maloom", 35)
        w("samajh", 45); w("samjha", 40); w("samjho", 40)

        // Question words / particles
        w("kya", 100); w("kia", 60); w("kyun", 80); w("kiun", 55); w("kyu", 50)
        w("kaise", 80); w("kese", 55); w("kab", 75); w("kahan", 75); w("kahaan", 50)
        w("kaha", 55); w("kitna", 65); w("kitni", 55); w("kaunsa", 40)
        w("agar", 70); w("magar", 65); w("lekin", 75); w("phir", 70); w("fir", 55)
        w("bhi", 90); w("bi", 30); w("nahi", 95); w("nai", 60); w("nahin", 55)
        w("haan", 85); w("han", 60); w("ji", 80); w("jee", 40)

        // Time / people / everyday nouns
        w("waqt", 55); w("time", 40); w("din", 65); w("raat", 60); w("subha", 50)
        w("subah", 45); w("shaam", 45); w("aaj", 75); w("kal", 75); w("abhi", 80)
        w("ghar", 70); w("kaam", 75); w("cheez", 55); w("baat", 75); w("log", 65)
        w("banda", 45); w("bandi", 30); w("dost", 65); w("yaar", 70); w("bhai", 80)
        w("behn", 45); w("bhen", 35); w("ami", 45); w("abu", 45); w("dil", 55)
        w("zindagi", 45); w("pyar", 55); w("mohabbat", 35); w("dosti", 35)

        // Common adjectives / fillers
        w("bara", 55); w("bari", 45); w("bare", 40); w("chota", 50); w("choti", 45)
        w("acha", 95); w("bura", 45); w("sahi", 65); w("galat", 45); w("thora", 60)
        w("thoda", 50); w("zyada", 65); w("jyada", 45); w("kam", 55)
        w("mushkil", 40); w("asaan", 35); w("aasan", 30)

        // Chat-speak / texting particles
        w("yr", 40); w("bs", 45); w("bas", 60); w("wese", 40); w("waise", 45)
        w("acha", 95); w("ok", 70); w("okay", 60); w("plz", 50); w("please", 45)
        w("sorry", 60); w("thanks", 55)
    }

    /** Fast membership check, lowercase. */
    fun contains(word: String): Boolean = words.containsKey(word.lowercase())

    fun frequencyOf(word: String): Int = words[word.lowercase()] ?: 0

    /** All words, for prefix scans etc. */
    fun allWords(): Set<String> = words.keys
}
