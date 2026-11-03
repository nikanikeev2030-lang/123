package ru.arenahelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


public final class CardMatcher {
    private final List<Entry> entries = new ArrayList();

    private static final class Entry {
        final Card card;
        final String name;

        Entry(String str, Card card) {
            this.name = str;
            this.card = card;
        }
    }

    public CardMatcher(List<Card> list) {
        LinkedHashMap<String,Card> linkedHashMap = new LinkedHashMap<>();
        for (Card card : list) {
            String strNormalize = TextNormalizer.normalize(card.getName());
            if (strNormalize.length() >= 3 && !linkedHashMap.containsKey(strNormalize)) {
                linkedHashMap.put(strNormalize, card);
            }
        }
        for (Map.Entry<String,Card> entry : linkedHashMap.entrySet()) {
            this.entries.add(new Entry((String) entry.getKey(), (Card) entry.getValue()));
        }
    }

    public Match match(String str) {
        Set<String> setSegments = segments(str);
        Match match = null;
        if (setSegments.isEmpty()) {
            return null;
        }
        ArrayList arrayList = new ArrayList();
        for (Entry entry : this.entries) {
            double d = 0.0d;
            for (String str2 : setSegments) {
                if (Math.abs(str2.length() - entry.name.length()) <= Math.max(3, entry.name.length() / 4)) {
                    double dDistance = 1.0d - (((double) TextNormalizer.distance(str2, entry.name)) / ((double) Math.max(str2.length(), entry.name.length())));
                    int iCommonPrefix = commonPrefix(str2, entry.name);
                    if (iCommonPrefix >= 6 && iCommonPrefix >= Math.min(str2.length(), entry.name.length()) - 1 && Math.abs(str2.length() - entry.name.length()) <= 2) {
                        dDistance = Math.max(dDistance, Math.min(0.06d, ((double) (iCommonPrefix - 6)) * 0.01d) + 0.86d);
                    }
                    if (dDistance > d) {
                        d = dDistance;
                        match = null;
                    }
                }
                match = null;
            }
            if (d >= (entry.name.length() <= 3 ? 0.999d : entry.name.length() < 8 ? 0.8d : 0.82d)) {
                arrayList.add(new Match(entry.card, d));
            }
        }
        arrayList.sort(new Comparator() { // from class: ru.arenahelper.CardMatcher$$ExternalSyntheticLambda0
            @Override // java.util.Comparator
            public final int compare(Object obj, Object obj2) {
                return Double.compare(((Match) obj2).getConfidence(), ((Match) obj).getConfidence());
            }
        });
        if (arrayList.isEmpty()) {
            return match;
        }
        return (arrayList.size() <= 1 || ((Match) arrayList.get(0)).getConfidence() - ((Match) arrayList.get(1)).getConfidence() >= 0.06d) ? (Match) arrayList.get(0) : match;
    }

    public Match matchLong(String text) {
        Match best=null;double second=0;
        for(Entry e:entries) {
            if(e.name.length()<20)continue;
            double score=0;
            for(String piece:segments(text)) {
                if(piece.length()<18 || Math.abs(piece.length()-e.name.length())>e.name.length()/4)continue;
                score=Math.max(score,1.0-(double)TextNormalizer.distance(piece,e.name)/Math.max(piece.length(),e.name.length()));
            }
            if(best==null || score>best.getConfidence()){if(best!=null)second=best.getConfidence();best=new Match(e.card,score);}else second=Math.max(second,score);
        }
        return best!=null && best.getConfidence()>=.73 && best.getConfidence()-second>=.12?best:null;
    }

    private static int commonPrefix(String str, String str2) {
        int iMin = Math.min(str.length(), str2.length());
        int i = 0;
        while (i < iMin && str.charAt(i) == str2.charAt(i)) {
            i++;
        }
        return i;
    }

    private static Set<String> segments(String str) {
        LinkedHashSet linkedHashSet = new LinkedHashSet();
        if (str == null) {
            return linkedHashSet;
        }
        add(linkedHashSet, str);
        String[] strArrSplit = str.split("\\r?\\n");
        ArrayList arrayList = new ArrayList();
        int i = 0;
        for (String str2 : strArrSplit) {
            String strNormalize = TextNormalizer.normalize(str2);
            if (strNormalize.length() >= 3 && arrayList.size() < 12) {
                arrayList.add(strNormalize);
            }
        }
        Iterator it = arrayList.iterator();
        while (it.hasNext()) {
            add(linkedHashSet, (String) it.next());
        }
        while (true) {
            int i2 = i + 1;
            if (i2 >= arrayList.size()) {
                return linkedHashSet;
            }
            add(linkedHashSet, String.valueOf((String) arrayList.get(i)) + ((String) arrayList.get(i2)));
            i = i2;
        }
    }

    private static void add(Set<String> set, String str) {
        String strNormalize = TextNormalizer.normalize(str);
        if (strNormalize.length() >= 3) {
            set.add(strNormalize);
        }
    }
}
