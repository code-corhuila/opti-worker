package co.edu.corhuila.opti.worker.adapter.out.customers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import co.edu.corhuila.opti.worker.adapter.out.http.ApiClient;
import co.edu.corhuila.opti.worker.application.port.out.PatientsApi;

/** Patients through the customers API: never through its database. */
public class CustomersHttpClient implements PatientsApi {

    private final ApiClient api;
    private final String customersUrl;

    public CustomersHttpClient(ApiClient api, String customersUrl) {
        this.api = api;
        this.customersUrl = customersUrl;
    }

    @Override
    public List<UUID> withControlOlderThan(LocalDate lastControlBefore, int limit) {
        JsonNode page = api.get(customersUrl + "/api/v1/patients?status=ACTIVE&controlDueBefore=" + lastControlBefore
                + "&limit=" + limit);
        List<UUID> ids = new ArrayList<>();
        page.path("data").forEach(patient -> ids.add(UUID.fromString(patient.path("id").asText())));
        return ids;
    }

    @Override
    public void flagControlOverdue(UUID patientId) {
        api.post(customersUrl + "/api/v1/patients/" + patientId + "/control-overdue", java.util.Map.of(), null);
    }
}
