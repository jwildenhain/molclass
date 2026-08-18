package molclass.predictor;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v3")
public class V3PredictionController {
    private final V3PredictionService service;

    public V3PredictionController(V3PredictionService service) {
        this.service = service;
    }

    @GetMapping("/models")
    public List<Map<String, Object>> models(
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "limit", defaultValue = "100") int limit) throws Exception {
        return service.searchModels(query, limit);
    }

    @GetMapping("/molecules")
    public List<Map<String, Object>> molecules(
            @RequestParam("query") String query,
            @RequestParam(value = "limit", defaultValue = "25") int limit) throws Exception {
        return service.searchMolecules(query, limit);
    }

    @GetMapping("/molecules/substructure")
    public V3PredictionService.SubstructureSearchResult substructure(
            @RequestParam("smiles") String smiles,
            @RequestParam(value = "limit", defaultValue = "25") int limit) throws Exception {
        return service.substructureSearch(smiles, limit);
    }

    @PostMapping("/models/{modelDefinitionId}/molecules/{moleculeId}/predict")
    public V3PredictionService.Prediction predict(
            @PathVariable long modelDefinitionId, @PathVariable long moleculeId) throws Exception {
        return service.predict(modelDefinitionId, moleculeId);
    }
}
