package com.odel.academic.service;

import com.odel.academic.dto.CentreDtos.*;
import com.odel.academic.dto.PeerDtos.*;
import com.odel.academic.entity.*;
import com.odel.academic.repository.*;
import com.odel.academic.client.*;

import com.odel.common.ApiException;
import com.odel.common.audit.AuditService;
import com.odel.common.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;

/** Study centres and their rooms (W-14 … W-14d). The coordinator shown comes from Staffing. */
@Service
public class CentreService {
    public static final Set<String> STATES = Set.of("Abia", "Adamawa", "Akwa Ibom", "Anambra", "Bauchi", "Bayelsa", "Benue", "Borno",
            "Cross River", "Delta", "Ebonyi", "Edo", "Ekiti", "Enugu", "FCT", "Gombe", "Imo", "Jigawa", "Kaduna", "Kano", "Katsina", "Kebbi",
            "Kogi", "Kwara", "Lagos", "Nasarawa", "Niger", "Ogun", "Ondo", "Osun", "Oyo", "Plateau", "Rivers", "Sokoto", "Taraba", "Yobe", "Zamfara");
    private static final String PHONE = "^(\\+234|0)[789][01]\\d{8}$";

    private final CentreRepository centres;
    private final RoomRepository rooms;
    private final StaffAssignmentRepository assignments;
    private final SessionService sessions;
    private final IdentityClient identity;
    private final StudentsClient studentsClient;
    private final AuditService audit;
    private final Clock clock;

    public CentreService(CentreRepository centres, RoomRepository rooms, StaffAssignmentRepository assignments, SessionService sessions,
                         IdentityClient identity, StudentsClient students, AuditService audit, Clock clock) {
        this.centres = centres;
        this.rooms = rooms;
        this.assignments = assignments;
        this.sessions = sessions;
        this.identity = identity;
        this.studentsClient = students;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CentreView> list() {
        Map<UUID, UUID> coord = coordinators();
        Map<UUID, Staff> staff = coord.isEmpty() ? Map.of() : identity.staffById();
        StudentsSummary students = studentsClient.summary();
        Map<UUID, List<Room>> byCentre = rooms.findAll().stream().collect(Collectors.groupingBy(Room::getCentreId));
        return centres.sorted().stream().map(c -> view(c, byCentre.getOrDefault(c.getId(), List.of()), coord, staff, students, false)).toList();
    }

    @Transactional(readOnly = true)
    public CentreView get(UUID id) {
        Centre c = centres.findById(id).orElseThrow(ApiException::notFound);
        Map<UUID, UUID> coord = coordinators();
        return view(c, rooms.findByCentreId(id), coord, coord.containsKey(id) ? identity.staffById() : Map.of(), studentsClient.summary(), true);
    }

    private Map<UUID, UUID> coordinators() {
        return sessions.current().map(s -> assignments.findBySessionId(s.getId()).stream().filter(a -> a.getKind() == StaffAssignment.Kind.COORDINATOR)
                .collect(Collectors.toMap(StaffAssignment::getCentreId, StaffAssignment::getUserId, (a, b) -> a))).orElse(Map.of());
    }

    private static CentreView view(Centre c, List<Room> rs, Map<UUID, UUID> coord, Map<UUID, Staff> staff, StudentsSummary students, boolean withRooms) {
        UUID co = coord.get(c.getId());
        Coordinator coordinator = co == null ? null : new Coordinator(co, Optional.ofNullable(staff.get(co)).map(Staff::fullName).orElse("Former staff member"));
        return new CentreView(c.getId(), c.getName(), c.getCode(), c.getAddress(), c.getState(), c.getPhone(), coordinator, rs.size(),
                rs.stream().mapToInt(Room::getSeats).sum(), rs.stream().mapToInt(Room::getComputers).sum(), students.atCentre(c.getId()),
                withRooms ? rs.stream().map(r -> new RoomView(r.getId(), r.getName(), r.getSeats(), r.getComputers())).toList() : null);
    }

    static Map<String, String> checkCentre(CentreIn in) {
        Map<String, String> f = new LinkedHashMap<>();
        if (Codes.blank(in.name())) f.put("name", "Enter the centre's name, for example Akure Study Centre.");
        if (Codes.blank(in.code()) || !Codes.unit(in.code()).matches(Codes.CENTRE)) f.put("code", "Use 2 to 6 letters or numbers, for example AKR.");
        if (!Codes.blank(in.state()) && STATES.stream().noneMatch(s -> s.equalsIgnoreCase(in.state().trim()))) f.put("state", "Choose one of the 36 states or the FCT.");
        if (!Codes.blank(in.phone()) && !in.phone().replaceAll("[\\s-]", "").matches(PHONE)) f.put("phone", "Enter a Nigerian phone number, for example 0803 123 4567.");
        return f;
    }

    @Transactional
    public CentreView save(UUID id, CentreIn in, AuditService.Actor actor) {
        StructureService.fail(checkCentre(in));
        String code = Codes.unit(in.code()), name = in.name().trim();
        centres.findByCode(code).filter(x -> !x.getId().equals(id)).ifPresent(x -> StructureService.taken(code, x.getName()));
        centres.findByName(name).filter(x -> !x.getId().equals(id)).ifPresent(x -> StructureService.nameTaken("a centre called " + name));
        Centre c = id == null ? new Centre() : centres.findById(id).orElseThrow(ApiException::notFound);
        if (id == null) { c.setId(UUID.randomUUID()); c.setCreatedAt(clock.instant()); }
        c.setName(name);
        c.setCode(code);
        c.setAddress(Codes.blank(in.address()) ? null : in.address().trim());
        c.setState(Codes.blank(in.state()) ? null : STATES.stream().filter(s -> s.equalsIgnoreCase(in.state().trim())).findFirst().orElse(null));
        c.setPhone(Codes.blank(in.phone()) ? null : in.phone().trim());
        c.setUpdatedAt(clock.instant());
        centres.saveAndFlush(c);
        audit.record(new AuditService.Entry(TenantContext.require(), actor, id == null ? "CENTRE_CREATED" : "CENTRE_UPDATED", "centre", c.getId(),
                (id == null ? "Added the study centre " : "Edited the study centre ") + name, null, null));
        return get(c.getId());
    }

    static Map<String, String> checkRoom(RoomIn in) {
        Map<String, String> f = new LinkedHashMap<>();
        if (Codes.blank(in.name())) f.put("name", "Enter the room's name, for example Room 1.");
        if (in.seats() == null || in.seats() < 1 || in.seats() > 2000) f.put("seats", "Enter the number of seats (1 to 2,000).");
        if (in.computers() == null || in.computers() < 0) f.put("computers", "Enter the number of computers (0 if none).");
        else if (in.seats() != null && in.computers() > in.seats()) f.put("computers", "A room cannot have more computers than seats.");
        return f;
    }

    @Transactional
    public RoomView addRoom(UUID centreId, RoomIn in, AuditService.Actor actor) {
        Centre c = centres.findById(centreId).orElseThrow(ApiException::notFound);
        return saveRoom(c, new Room(), in, actor, true);
    }

    @Transactional
    public RoomView updateRoom(UUID roomId, RoomIn in, AuditService.Actor actor) {
        Room r = rooms.findById(roomId).orElseThrow(ApiException::notFound);
        return saveRoom(centres.findById(r.getCentreId()).orElseThrow(), r, in, actor, false);
    }

    private RoomView saveRoom(Centre c, Room r, RoomIn in, AuditService.Actor actor, boolean isNew) {
        StructureService.fail(checkRoom(in));
        String name = in.name().trim();
        rooms.findByCentreAndName(c.getId(), name).filter(x -> !x.getId().equals(r.getId())).ifPresent(x ->
                StructureService.nameTaken("a room called " + name + " at " + c.getName()));
        if (isNew) { r.setId(UUID.randomUUID()); r.setCentreId(c.getId()); r.setCreatedAt(clock.instant()); }
        r.setName(name);
        r.setSeats(in.seats());
        r.setComputers(in.computers());
        rooms.saveAndFlush(r);
        audit.record(new AuditService.Entry(TenantContext.require(), actor, isNew ? "ROOM_CREATED" : "ROOM_UPDATED", "room", r.getId(),
                (isNew ? "Added " : "Edited ") + name + " at " + c.getName() + " (" + r.getSeats() + " seats, " + r.getComputers() + " computers)", null, null));
        return new RoomView(r.getId(), r.getName(), r.getSeats(), r.getComputers());
    }

    @Transactional
    public void deleteRoom(UUID roomId, AuditService.Actor actor) {
        Room r = rooms.findById(roomId).orElseThrow(ApiException::notFound);
        Centre c = centres.findById(r.getCentreId()).orElseThrow();
        rooms.delete(r);
        audit.record(new AuditService.Entry(TenantContext.require(), actor, "ROOM_DELETED", "room", roomId, "Removed " + r.getName() + " from " + c.getName(), null, null));
    }

    static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
