package jyrs.dev.vivesbank.movements.services;
import jyrs.dev.vivesbank.movements.exceptions.MovementNotFoundException;
import jyrs.dev.vivesbank.movements.exceptions.MovementNotReversible;
import jyrs.dev.vivesbank.movements.exceptions.MovementRecipientNotFound;
import jyrs.dev.vivesbank.movements.exceptions.MovementSenderNotFound;
import jyrs.dev.vivesbank.movements.models.Movement;
import jyrs.dev.vivesbank.movements.repository.MovementsRepository;
import jyrs.dev.vivesbank.movements.storage.MovementPdfGenerator;
import jyrs.dev.vivesbank.movements.storage.MovementsStorage;
import jyrs.dev.vivesbank.movements.validator.MovementValidator;
import jyrs.dev.vivesbank.products.bankAccounts.models.BankAccount;
import jyrs.dev.vivesbank.users.clients.exceptions.ClientNotFound;
import jyrs.dev.vivesbank.users.clients.repository.ClientsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class MovementsServiceImpl implements MovementsService {

    private final MovementsRepository movementsRepository;
    private final ClientsRepository clientsRepository;
    private final MovementValidator movementValidator;
    private final MovementPdfGenerator pdfGenerator;
    private final MovementsStorage storage;
    private final RedisTemplate<String, Movement> redisTemplate;

    @Autowired
    public MovementsServiceImpl(MovementsRepository movementsRepository, ClientsRepository clientsRepository, MovementValidator movementValidator, MovementsStorage storage, RedisTemplate<String, Movement> redisTemplate) {
        this.movementsRepository = movementsRepository;
        this.clientsRepository = clientsRepository;
        this.movementValidator = movementValidator;
        this.storage = storage;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void createMovement(String senderClientId, String recipientClientId,
                               BankAccount origin, BankAccount destination, String typeMovement,
                               Double amount) {

        var senderClient = clientsRepository.findById(Long.parseLong(senderClientId))
                .orElseThrow(() -> new MovementSenderNotFound("Cliente remitente no encontrado."));

        var recipientClient = recipientClientId != null
                ? clientsRepository.findById(Long.parseLong(recipientClientId))
                .orElseThrow(() -> new MovementRecipientNotFound("Cliente receptor no encontrado."))
                : null;

        var movement = Movement.builder()
                .senderClient(senderClient)
                .recipientClient(recipientClient)
                .origin(origin)
                .destination(destination)
                .typeMovement(typeMovement)
                .date(LocalDateTime.now())
                .amount(amount)
                .balance(senderClient.getCuentas() != null ? senderClient.getCuentas().stream().mapToDouble(cuenta -> cuenta != null ? Double.parseDouble(String.valueOf(cuenta)) : 0.0).sum() - amount : 0.0)
                .isReversible(true)
                .transferDeadlineDate(LocalDateTime.now().plusDays(7))
                .build();

        redisTemplate.opsForValue().set("MOVEMENT:" + movement.getId(), movement);

        movementsRepository.save(movement);
    }

    @Override
    public void reverseMovement(String movementId) {
        var movement = movementsRepository.findById(movementId)
                .orElseThrow(() -> new MovementNotFoundException("Movimiento no encontrado."));

        movementValidator.validateReversible(movement);

        if (!movement.getIsReversible()) {
            throw new MovementNotReversible("No se puede revertir este movimiento.");
        }

        movement.setIsReversible(false);
        movementsRepository.save(movement);

        redisTemplate.opsForValue().set("MOVEMENT:" + movementId, movement);
    }

    @Override
    public List<Movement> getAllMovements(Long clientId) {
        if (!clientsRepository.existsById(clientId)) {
            throw new ClientNotFound("El cliente con ID " + clientId + " no existe.");
        }

        String redisKey = "MOVEMENTS:ALL:" + clientId;

        List<Movement> movements = (List<Movement>) redisTemplate.opsForValue().get(redisKey);

        if (movements == null || movements.isEmpty()) {
            List<Movement> sentMovements = movementsRepository.findBySenderClient_Id(String.valueOf(clientId));
            List<Movement> receivedMovements = movementsRepository.findByRecipientClient_Id(String.valueOf(clientId));

            movements = new ArrayList<>();
            movements.addAll(sentMovements);
            movements.addAll(receivedMovements);

            for (Movement mov : movements) {
                redisTemplate.opsForValue().set(redisKey + ":" + mov.getId(), mov);
            }
        }

        return movements;
    }

    @Override
    public List<Movement> getAllMyMovements(Long clientId) {

        if (!clientsRepository.existsById(Long.valueOf(clientId))) {
            throw new ClientNotFound("El cliente autenticado no existe.");
        }

        String redisKey = "MOVEMENTS:ALL:MY:" + clientId;

        List<Movement> movements = (List<Movement>) redisTemplate.opsForValue().get(redisKey);

        if (movements == null || movements.isEmpty()) {
            List<Movement> sentMovements = movementsRepository.findBySenderClient_Id(String.valueOf(clientId));
            List<Movement> receivedMovements = movementsRepository.findByRecipientClient_Id(String.valueOf(clientId));

            movements = new ArrayList<>();
            movements.addAll(sentMovements);
            movements.addAll(receivedMovements);

            for (Movement mov : movements) {
                redisTemplate.opsForValue().set(redisKey + ":" + mov.getId(), mov);
            }
        }

        return movements;
    }


    @Override
    public List<Movement> getAllSentMovements(String clientId) {
        var client = clientsRepository.getByUser_Guuid(clientId).orElseThrow(()-> new ClientNotFound(clientId));

        String redisKey = "MOVEMENTS:SENT:" + clientId;

        List<Movement> movements = (List<Movement>) redisTemplate.opsForValue().get(redisKey);

        if (movements == null || movements.isEmpty()) {
            movements = movementsRepository.findBySenderClient_Id((clientId));

            if (!movements.isEmpty()) {
                redisTemplate.opsForValue().set(redisKey, (Movement) movements);
            }
        }
        return movements;
    }

    @Override
    public List<Movement> getAllReceivedMovements(String clientId) {
        var client = clientsRepository.getByUser_Guuid(clientId).orElseThrow(()-> new ClientNotFound(clientId));

        String redisKey = "MOVEMENTS:RECEIVED:" + clientId;

        List<Movement> movements = (List<Movement>) redisTemplate.opsForValue().get(redisKey);

        if (movements == null || movements.isEmpty()) {
            movements = movementsRepository.findByRecipientClient_Id(String.valueOf(clientId));

            if (!movements.isEmpty()) {
                redisTemplate.opsForValue().set(redisKey, (Movement) movements);
            }
        }

        return movements;
    }


    @Override
    public List<Movement> getMovementsByClientId(String clientId) {
        List<Movement> movements = new ArrayList<>();

        String redisKey = "MOVEMENTS:CLIENT:" + clientId;
        Movement movement = redisTemplate.opsForValue().get(redisKey);

        if (movement == null) {
            var sentMovements = movementsRepository.findBySenderClient_Id(clientId);
            var receivedMovements = movementsRepository.findByRecipientClient_Id(clientId);

            movements.addAll(sentMovements);
            movements.addAll(receivedMovements);

            for (Movement mov : movements) {
                redisTemplate.opsForValue().set(redisKey + ":" + mov.getId(), mov);
            }
        }

        return movements;
    }


    @Override
    public List<Movement> getMovementsByType(String typeMovement) {
        List<Movement> movements = new ArrayList<>();

        String redisKey = "MOVEMENTS:TYPE:" + typeMovement;

        for (int i = 0; i < 100; i++) {
            Movement movement = redisTemplate.opsForValue().get(redisKey + ":" + i);
            if (movement == null) {
                break;
            }
            movements.add(movement);
        }

        if (movements.isEmpty()) {
            movements = movementsRepository.findByTypeMovement(typeMovement);

            for (int i = 0; i < movements.size(); i++) {
                redisTemplate.opsForValue().set(redisKey + ":" + i, movements.get(i));
            }
        }

        return movements;
    }



    @Override
    public void deleteMovement(String movementId) {
        var movement = movementsRepository.findById(movementId)
                .orElseThrow(() -> new MovementNotFoundException("Movements no encontrado."));

        movementsRepository.delete(movement);

        redisTemplate.delete("MOVEMENT:" + movementId);
    }

    @Override
    public void exportJson(File file, List<Movement> movements) {
        log.info("Exportando movements a JSON");

        storage.exportJson(file, movements);
    }

    @Override
    public void importJson(File file) {
        log.info("Importando movements desde JSON");

        List<Movement> movements = storage.importJson(file);

        movementsRepository.saveAll(movements);
    }

    @Override
    public File generateMovementPdf(String id) {

        var movement = movementsRepository.findById(id).orElseThrow();//TODO Excepcion

        return pdfGenerator.generateMovementPdf(movement);
    }

    @Override
    public File generateMeMovementPdf(String idCl,String idMv) {
        var cliente = clientsRepository.getByUser_Guuid(idCl).orElseThrow(() -> new ClientNotFound(idCl));

        var movement = movementsRepository.findById(idMv).orElseThrow();//TODO Excepcion

        if (movement.getSenderClient().getId() != cliente.getId() || movement.getRecipientClient().getId() != cliente.getId()){
            //TODO Excepcion de qeu el movimiendto no le pertenece o no tiene ese movimiento
        }

        return pdfGenerator.generateMovementPdf(movement);
    }

    @Override
    public File generateAllMeMovementPdf(String id) {

        var cliente = clientsRepository.getByUser_Guuid(id).orElseThrow(() -> new ClientNotFound(id));

        var lista = getMovementsByClientId(cliente.getUser().getGuuid());

        return pdfGenerator.generateMovementsPdf(lista, Optional.of(cliente));
    }

    @Override
    public File generateAllMeMovementSendPdf(String id) {

        var cliente = clientsRepository.getByUser_Guuid(id).orElseThrow(() -> new ClientNotFound(id));

        var lista = movementsRepository.findBySenderClient_Id(cliente.getUser().getGuuid());

        return pdfGenerator.generateMovementsPdf(lista, Optional.of(cliente));
    }

    @Override
    public File generateAllMeMovementRecepientPdf(String id) {

        var cliente = clientsRepository.getByUser_Guuid(id).orElseThrow(() -> new ClientNotFound(id));

        var lista = movementsRepository.findByRecipientClient_Id(cliente.getUser().getGuuid());

        return pdfGenerator.generateMovementsPdf(lista, Optional.of(cliente));
    }

    @Override
    public File generateAllMovementPdf() {

        var lista = getAllMovements();

        return pdfGenerator.generateMovementsPdf(lista, Optional.empty());
    }

}


