package com.boatpilot.client;

import com.boatpilot.maps.MapSource;
import com.boatpilot.navigation.RoutePlan;
import com.boatpilot.navigation.RouteDistance;
import com.boatpilot.navigation.RoutePlanner;
import com.boatpilot.navigation.SurfaceType;
import com.boatpilot.maps.WaypointSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

public final class AutopilotController {
    private static final double MAX_ROUTE_DISTANCE = 4096.0;
    private static final int MAX_RECOVERY_ATTEMPTS = 2;
    private static final int RECOVERY_REVERSE_TICKS = 20;
    private static final int RECOVERY_TURN_TICKS = 20;
    private static final int RECOVERY_TOTAL_TICKS = 50;
    private static final Minecraft MC = Minecraft.getInstance();
    private static final RoutePlanner ROUTER = new RoutePlanner();
    private static MapSource mapSource;
    private static WaypointSource waypointSource;
    private static List<BlockPos> route = List.of();
    private static int nextPoint;
    private static boolean planning;
    private static boolean navigating;
    private static int stalledTicks;
    private static int collisionTicks;
    private static int recoveryTicks;
    private static int recoveryAttempts;
    private static boolean recovering;
    private static boolean recoveryTurnLeft;
    private static Vec3 recoveryAnchor = Vec3.ZERO;
    private static com.boatpilot.navigation.MapRaster activeMap;
    private static Vec3 previousPosition = Vec3.ZERO;
    private static long planRevision;

    private AutopilotController() {
    }

    public static void initialize(MapSource source, WaypointSource waypoints) {
        mapSource = source;
        waypointSource = waypoints;
    }

    public static void selectWaypoint(String name) {
        if (waypointSource == null || MC.level == null) {
            tell("JourneyMap non è disponibile.");
            return;
        }
        waypointSource.findByName(name, waypoint -> waypoint.ifPresentOrElse(
            thisPosition -> requestRoute(thisPosition),
            () -> tell("Waypoint non trovato o nome ambiguo in questa dimensione: " + name)));
    }

    public static void requestRoute(BlockPos target) {
        AbstractBoat boat = controlledBoat();
        if (boat == null) {
            tell("ERRORE: sali su una barca prima di avviare il pilota automatico.");
            return;
        }
        if (mapSource == null) {
            report("ERRORE: JourneyMap non collegata", "Verifica che JourneyMap 26.3 Forge sia installata e caricata.");
            return;
        }
        BlockPos start = BlockPos.containing(boat.position());
        double distance = RouteDistance.horizontal(start, target);
        String distanceLabel = Math.round(distance) + " blocchi in linea d'aria";
        if (distance > MAX_ROUTE_DISTANCE) {
            report("ERRORE: " + Math.round(distance) + " blocchi, massimo " + (int) MAX_ROUTE_DISTANCE,
                "Distanza orizzontale " + distanceLabel + ". Partenza " + coordinates(start)
                    + ", destinazione " + coordinates(target) + ". Riduci la distanza e riprova.");
            return;
        }
        stop(true);
        planning = true;
        long requestRevision = ++planRevision;
        tell("BOATPILOT: calcolo rotta, " + distanceLabel + ".");
        mapSource.request(start, target, raster -> {
            if (requestRevision != planRevision) return;
            planning = false;
            if (raster.isEmpty()) {
                report("ERRORE: JourneyMap senza dati, " + Math.round(distance) + " blocchi",
                    "JourneyMap non contiene superfici riconoscibili nel rettangolo tra partenza "
                        + coordinates(start) + " e destinazione " + coordinates(target)
                        + ". Esplora queste zone e controlla che compaiano sulla mappa.");
                return;
            }
            var map = raster.get();
            RoutePlan plan = ROUTER.plan(map, start, target);
            switch (plan.status()) {
                case READY -> {
                    activeMap = map;
                    route = plan.path();
                    nextPoint = 0;
                    navigating = true;
                    tell("ROTTA PRONTA: " + Math.round(distance) + " blocchi"
                        + (plan.snappedFromLand() ? "; arrivo in acqua vicina." : ". Premi O per fermare."));
                    if (plan.snappedFromLand()) {
                        chatDetail("Destinazione " + coordinates(target) + " a terra; guiderò fino all'acqua collegata più vicina.");
                    }
                }
                case UNKNOWN -> {
                    boolean startUnknown = map.surface(map.pixelX(start.getX()), map.pixelZ(start.getZ())) == SurfaceType.UNKNOWN;
                    boolean targetUnknown = map.surface(map.pixelX(target.getX()), map.pixelZ(target.getZ())) == SurfaceType.UNKNOWN;
                    String reason = startUnknown && targetUnknown
                        ? "partenza e destinazione non sono mappate"
                        : startUnknown ? "la posizione della barca non è mappata"
                        : targetUnknown ? "la destinazione non è mappata"
                        : "la mappa ha un tratto sconosciuto tra partenza e destinazione";
                    report("ERRORE: " + reason + " (" + Math.round(distance) + " blocchi)",
                        "Distanza " + distanceLabel + ". Partenza " + coordinates(start) + ", destinazione "
                            + coordinates(target) + ". Esplora il tratto mancante con JourneyMap e riprova.");
                }
                case UNREACHABLE -> report("ERRORE: nessun collegamento d'acqua (" + Math.round(distance) + " blocchi)",
                    "La mappa conosce la zona, ma non trova un canale d'acqua collegato dalla barca in "
                        + coordinates(start) + " alla destinazione " + coordinates(target) + ". Verifica che non ci siano terre o canali chiusi.");
                case NO_WATER_START -> report("ERRORE: partenza non classificata come acqua",
                    "JourneyMap non classifica " + coordinates(start) + " come acqua. Sposta la barca in acqua mappata e riprova (" + distanceLabel + ").");
            }
        });
    }

    public static void tick() {
        if (!navigating) return;
        AbstractBoat boat = controlledBoat();
        if (boat == null) {
            stop(true);
            tell("Guida interrotta: non sei più sulla barca.");
            return;
        }
        if (recovering) {
            tickRecovery(boat);
            return;
        }
        if (boat.horizontalCollision) {
            collisionTicks++;
            releaseMovementKeys();
            if (collisionTicks == 1) tell("Collisione: provo il recupero se non si libera entro 3 secondi.");
            if (collisionTicks >= 60) {
                beginRecovery(boat, "collisione persistente");
            }
            return;
        }
        collisionTicks = 0;
        while (nextPoint < route.size()
            && horizontalDistanceSquared(boat.position(), route.get(nextPoint)) < 9.0) {
            nextPoint++;
        }
        if (nextPoint >= route.size()) {
            stop(true);
            tell("Destinazione raggiunta.");
            return;
        }

        Vec3 target = Vec3.atCenterOf(route.get(nextPoint));
        Vec3 position = boat.position();
        Vec3 delta = target.subtract(position);
        float desiredYaw = (float) (Math.atan2(-delta.x, delta.z) * Mth.RAD_TO_DEG);
        float yawError = Mth.wrapDegrees(desiredYaw - boat.getYRot());
        MC.options.keyUp.setDown(Math.abs(yawError) < 52.0f);
        MC.options.keyDown.setDown(false);
        MC.options.keyLeft.setDown(yawError < -10.0f);
        MC.options.keyRight.setDown(yawError > 10.0f);

        if (Math.abs(yawError) >= 52.0f) stalledTicks = 0;
        else if (position.distanceToSqr(previousPosition) < 0.0025) stalledTicks++;
        else stalledTicks = 0;
        if (recoveryAttempts > 0 && position.distanceToSqr(recoveryAnchor) > 4.0) {
            recoveryAttempts = 0;
            recoveryAnchor = Vec3.ZERO;
        }
        previousPosition = position;
        if (stalledTicks > 50) {
            beginRecovery(boat, "barca ferma da oltre 2,5 secondi");
        }
    }

    public static void manualInput() {
        if (navigating || planning) {
            stop(true);
            tell("Guida interrotta per input manuale.");
        }
    }

    public static void stopByUser() {
        stop(true);
        tell("Pilota automatico fermato.");
    }

    public static void stop(boolean releaseKeys) {
        planRevision++;
        planning = false;
        navigating = false;
        route = List.of();
        nextPoint = 0;
        stalledTicks = 0;
        collisionTicks = 0;
        recoveryTicks = 0;
        recoveryAttempts = 0;
        recovering = false;
        recoveryAnchor = Vec3.ZERO;
        activeMap = null;
        if (releaseKeys) releaseMovementKeys();
    }

    public static boolean isPlanning() { return planning; }
    public static boolean isNavigating() { return navigating; }

    private static AbstractBoat controlledBoat() {
        return MC.player != null && MC.player.getVehicle() instanceof AbstractBoat boat ? boat : null;
    }

    private static double horizontalDistanceSquared(Vec3 position, BlockPos target) {
        double dx = position.x - (target.getX() + 0.5);
        double dz = position.z - (target.getZ() + 0.5);
        return dx * dx + dz * dz;
    }

    private static String coordinates(BlockPos pos) {
        return "[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]";
    }

    private static void beginRecovery(AbstractBoat boat, String reason) {
        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
            stop(true);
            tell("RECUPERO FALLITO: " + reason + ". Ho provato " + MAX_RECOVERY_ATTEMPTS
                + " manovre; fermo il pilota per evitare di insistere sull'ostacolo.");
            return;
        }
        recoveryAttempts++;
        recoveryTicks = 0;
        collisionTicks = 0;
        stalledTicks = 0;
        recoveryAnchor = boat.position();
        recoveryTurnLeft = chooseRecoveryTurn(boat);
        recovering = true;
        tell("RECUPERO " + recoveryAttempts + "/" + MAX_RECOVERY_ATTEMPTS + ": retro e deviazione per " + reason + ".");
        tickRecovery(boat);
    }

    private static void tickRecovery(AbstractBoat boat) {
        recoveryTicks++;
        MC.options.keyLeft.setDown(recoveryTurnLeft);
        MC.options.keyRight.setDown(!recoveryTurnLeft);
        if (recoveryTicks <= RECOVERY_REVERSE_TICKS) {
            MC.options.keyUp.setDown(false);
            MC.options.keyDown.setDown(true);
        } else if (recoveryTicks <= RECOVERY_REVERSE_TICKS + RECOVERY_TURN_TICKS) {
            MC.options.keyUp.setDown(true);
            MC.options.keyDown.setDown(false);
        } else if (recoveryTicks < RECOVERY_TOTAL_TICKS) {
            MC.options.keyUp.setDown(false);
            MC.options.keyDown.setDown(false);
            MC.options.keyLeft.setDown(false);
            MC.options.keyRight.setDown(false);
        } else {
            recovering = false;
            recoveryTicks = 0;
            collisionTicks = 0;
            stalledTicks = 0;
            previousPosition = boat.position();
            releaseMovementKeys();
            tell("Recupero completato: riprendo la rotta.");
        }
    }

    /** Choose the side with more known navigable water beside the current course. */
    private static boolean chooseRecoveryTurn(AbstractBoat boat) {
        if (activeMap == null || route.isEmpty()) return recoveryAttempts % 2 == 1;
        BlockPos target = route.get(Math.min(nextPoint, route.size() - 1));
        Vec3 delta = Vec3.atCenterOf(target).subtract(boat.position());
        double length = Math.max(1.0, Math.hypot(delta.x, delta.z));
        double leftX = -delta.z / length;
        double leftZ = delta.x / length;
        int leftScore = recoverySideScore(boat.position(), leftX, leftZ);
        int rightScore = recoverySideScore(boat.position(), -leftX, -leftZ);
        if (leftScore == rightScore) return recoveryAttempts % 2 == 1;
        return leftScore > rightScore;
    }

    private static int recoverySideScore(Vec3 origin, double sideX, double sideZ) {
        int score = 0;
        for (int distance : new int[]{4, 8, 12}) {
            for (int lateral = -2; lateral <= 2; lateral += 2) {
                int x = Mth.floor(origin.x + sideX * distance - sideZ * lateral);
                int z = Mth.floor(origin.z + sideZ * distance + sideX * lateral);
                int px = activeMap.pixelX(x);
                int pz = activeMap.pixelZ(z);
                SurfaceType surface = activeMap.surface(px, pz);
                score += surface == SurfaceType.WATER ? 2 : surface == SurfaceType.LAND ? -2 : -3;
            }
        }
        return score;
    }

    private static void releaseMovementKeys() {
        MC.options.keyUp.setDown(false);
        MC.options.keyDown.setDown(false);
        MC.options.keyLeft.setDown(false);
        MC.options.keyRight.setDown(false);
    }

    private static void tell(String message) {
        if (MC.player != null) MC.player.sendOverlayMessage(Component.literal(message));
    }

    private static void report(String hudMessage, String detail) {
        if (MC.player == null) return;
        MC.player.sendOverlayMessage(Component.literal(hudMessage));
        chatDetail(detail);
    }

    private static void chatDetail(String detail) {
        if (MC.gui != null) MC.gui.hud.getChat().addClientSystemMessage(Component.literal("[BoatPilot] " + detail));
    }
}
