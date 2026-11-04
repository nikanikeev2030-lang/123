package ru.arenahelper;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;


public final class DraftCatalog {
    private final Map<String, Details> cards = new LinkedHashMap();
    private final Map<String, PackageInfo> packages = new HashMap();
    private final Map<String, ClassStat> stats = new LinkedHashMap();
    private static final int[] TARGET = {6, 7, 6, 5, 3, 2, 1};
    private static final int[] BASE_FIT = {82, 96, 100, 92, 80, 68, 52};

    public static final class ClassStat {
        public int drafts;
        public boolean cached;
        public String id;
        public String label;
        public double winRate;
    }

    public static final class Details {
        public String name;
        public int cost = -1;
        public String rarity = "";
        public final Map<String, Integer> scores = new LinkedHashMap();
    }

    public static final class PackageInfo {
        public final List<DeckCard> cards = new ArrayList();
        public String key;
    }

    public static final class DeckCard {
        public int cost;
        public String name;

        public DeckCard(String str, int i) {
            this.name = str;
            this.cost = i;
        }
    }

    public DraftCatalog(Context context) throws Exception {
        JSONObject jSONObject = Catalog.INSTANCE.read(context);
        JSONArray jSONArray = jSONObject.getJSONArray("cards");
        for (int i = 0; i < jSONArray.length(); i++) {
            JSONObject jSONObject2 = jSONArray.getJSONObject(i);
            String string = jSONObject2.getString("name");
            String strNormalize = TextNormalizer.normalize(string);
            Details details = this.cards.get(strNormalize);
            if (details == null) {
                details = new Details();
                details.name = string;
                this.cards.put(strNormalize, details);
            }
            if (details.cost < 0 && jSONObject2.has("cost")) {
                details.cost = jSONObject2.optInt("cost", -1);
            }
            if (details.rarity.isEmpty()) {
                details.rarity = jSONObject2.optString("rarity", "");
            }
            JSONObject jSONObject3 = jSONObject2.getJSONObject("scores");
            Iterator<String> itKeys = jSONObject3.keys();
            while (itKeys.hasNext()) {
                String next = itKeys.next();
                details.scores.put(next, Integer.valueOf(jSONObject3.getInt(next)));
            }
        }
        if (jSONObject.has("packages")) {
            JSONArray jSONArray2 = jSONObject.getJSONArray("packages");
            for (int i2 = 0; i2 < jSONArray2.length(); i2++) {
                JSONObject jSONObject4 = jSONArray2.getJSONObject(i2);
                PackageInfo packageInfo = new PackageInfo();
                packageInfo.key = jSONObject4.getString("key");
                JSONArray jSONArray3 = jSONObject4.getJSONArray("cards");
                for (int i3 = 0; i3 < jSONArray3.length(); i3++) {
                    JSONObject jSONObject5 = jSONArray3.getJSONObject(i3);
                    packageInfo.cards.add(new DeckCard(jSONObject5.getString("name"), jSONObject5.optInt("cost", -1)));
                }
                this.packages.put(TextNormalizer.normalize(packageInfo.key), packageInfo);
            }
        }
        if (jSONObject.has("classStats")) {
            JSONArray jSONArray4 = jSONObject.getJSONArray("classStats");
            for (int i4 = 0; i4 < jSONArray4.length(); i4++) {
                JSONObject jSONObject6 = jSONArray4.getJSONObject(i4);
                ClassStat classStat = new ClassStat();
                classStat.id = jSONObject6.getString("id");
                classStat.label = jSONObject6.getString("label");
                classStat.winRate = jSONObject6.getDouble("winRate");
                classStat.drafts = jSONObject6.optInt("drafts");
                classStat.cached = jSONObject6.optBoolean("cached", false);
                this.stats.put(classStat.id, classStat);
            }
        }
    }

    public Collection<ClassStat> classStats() {
        return this.stats.values();
    }

    public Details details(String str) {
        return this.cards.get(TextNormalizer.normalize(str));
    }

    public PackageInfo packageFor(String str) {
        return this.packages.get(TextNormalizer.normalize(str));
    }

    public ClassStat stat(String str) {
        return this.stats.get(str);
    }

    public Integer score(String str, String str2) {
        Details details = details(str);
        if (details == null || details.scores.isEmpty()) {
            return null;
        }
        Integer num = details.scores.get(str2);
        if (num != null) {
            return num;
        }
        int size = details.scores.size();
        Iterator<Integer> it = details.scores.values().iterator();
        if (size == 1) {
            return it.next();
        }
        Integer numValueOf = null;
        while (it.hasNext()) {
            int iIntValue = it.next().intValue();
            if (numValueOf != null && numValueOf.intValue() != iIntValue) {
                return null;
            }
            numValueOf = Integer.valueOf(iIntValue);
        }
        return numValueOf;
    }

    public String inferClass(List<Match> list) {
        Details details;
        HashMap<String,Integer> map = new HashMap<>();
        HashSet hashSet = new HashSet();
        Iterator<Match> it = list.iterator();
        while (true) {
            if (!it.hasNext()) {
                break;
            }
            Match next = it.next();
            if (next != null && next.getConfidence() >= 0.82d && hashSet.add(TextNormalizer.normalize(next.getCard().getName())) && (details = details(next.getCard().getName())) != null && details.scores.size() == 1) {
                String next2 = details.scores.keySet().iterator().next();
                map.put(next2, Integer.valueOf(map.containsKey(next2) ? 1 + ((Integer) map.get(next2)).intValue() : 1));
            }
        }
        String str = null;
        int iIntValue = 0;
        boolean z = false;
        for (Map.Entry<String,Integer> entry : map.entrySet()) {
            if (((Integer) entry.getValue()).intValue() > iIntValue) {
                str = (String) entry.getKey();
                iIntValue = ((Integer) entry.getValue()).intValue();
                z = false;
            } else if (((Integer) entry.getValue()).intValue() == iIntValue) {
                z = true;
            }
        }
        if (iIntValue <= 0 || z) {
            return null;
        }
        return str;
    }

    public static int bucket(int i) {
        if (i <= 1) {
            return 0;
        }
        if (i == 2) {
            return 1;
        }
        if (i == 3) {
            return 2;
        }
        if (i == 4) {
            return 3;
        }
        if (i == 5) {
            return 4;
        }
        return i == 6 ? 5 : 6;
    }

    public static int knownCosts(List<Integer> costs) {
        int known = 0;
        for (Integer cost : costs) if (cost != null && cost >= 0) known++;
        return known;
    }

    public static Integer curveScore(int cost, List<Integer> costs) {
        if (cost < 0) return null;
        int bucket = bucket(cost), known = knownCosts(costs), matching = 0;
        for (Integer value : costs) {
            if (value != null && value >= 0 && bucket(value) == bucket) matching++;
        }
        // Unknown picks use a smoothed distribution: observed cards plus a six-card
        // prior proportional to the target curve. Never pretend unknown picks are known.
        int missing = costs.size() - known;
        double probability = (matching + 6.0 * TARGET[bucket] / 30.0) / (known + 6.0);
        double estimated = matching + missing * probability;
        return (int) Math.round(Math.max(10.0, Math.min(100.0,
                BASE_FIT[bucket] - estimated * 70.0 / TARGET[bucket])));
    }

    public static Integer packageCurve(Details details, PackageInfo packageInfo) {
        ArrayList arrayList = new ArrayList();
        ArrayList<DeckCard> arrayList2 = new ArrayList();
        arrayList2.add(new DeckCard(details.name, details.cost));
        if (packageInfo != null) {
            arrayList2.addAll(packageInfo.cards);
        }
        int i = 0;
        int iIntValue = 0;
        for (DeckCard deckCard : arrayList2) {
            Integer numCurveScore = curveScore(deckCard.cost, arrayList);
            if (numCurveScore != null) {
                iIntValue += numCurveScore.intValue();
                i++;
                arrayList.add(Integer.valueOf(deckCard.cost));
            }
        }
        if (i == 0) {
            return null;
        }
        return Integer.valueOf(Math.round(iIntValue / i));
    }

    public static Integer composite(Integer num, Integer num2) {
        if (num == null) {
            return null;
        }
        return Integer.valueOf((int) Math.round((((double) num.intValue()) * 0.7d) + (((double) (num2 == null ? 50 : num2.intValue())) * 0.3d)));
    }
}
