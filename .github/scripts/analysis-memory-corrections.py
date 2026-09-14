from pathlib import Path
p=Path('taxonomy-app/src/main/java/com/taxonomy/analysis/service/LlmService.java')
s=p.read_text()
s=s.replace('        if (products.isEmpty()) return detailFromScores(Map.of(), Map.of(), null);\n','')
assert 'config.getProductBatchSize()' in s
s=s.replace('Math.max(1, config.getProductBatchSize())','Math.max(1, Math.min(10, productBatchSize))')
s=s.replace('        return accumulator.result();', '''        LlmCallDetail result = accumulator.result();
        if (result.getProvider() == null) result.setProvider(getActiveProviderName());
        return result;''')
p.write_text(s)
