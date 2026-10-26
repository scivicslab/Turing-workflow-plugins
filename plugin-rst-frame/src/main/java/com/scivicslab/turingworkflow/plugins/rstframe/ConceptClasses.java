package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.armedbear.lisp.Interpreter;

/**
 * Organizes a hierarchy of concepts as CLOS classes in ABCL, where each concept is a class, a "kind-of" relationship
 * is a superclass, and the properties of a concept are slots whose documentation serves as the questions a paragraph
 * about that concept must answer.
 *
 * <ul><li>{@link #all()} returns every class with its documentation in name order.</li><li>{@link #names()} returns
 * the names of every class.</li><li>{@link #roots()} returns the classes with no superclass among these
 * classes.</li><li>{@link #subclasses(String)} returns the classes right under {@code cls}.</li><li>{@link
 * #chain(String)} returns {@code cls} and its superclasses, nearest first, each with its documentation.</li><li>{@link
 * #superclasses(String)} returns the classes {@code cls} is a direct subclass of.</li><li>{@link #find(String)}
 * returns the class name for a concept name, or {@code null} if no class or instance matches.</li><li>{@link
 * #instances()} returns the values of classes, by name, with the class each is a value of.</li><li>{@link
 * #whatCanBeSaid(String)} returns every slot the class has, both its own and inherited, in CLOS order.</li><li>{@link
 * #ownSlots(String)} returns the slots {@code cls} defines itself, without the ones it inherits.</li><li>{@link
 * #define(String, String, String)} defines a class with no slots of its own under {@code superclass} ({@code null} for
 * none).</li><li>{@link #addSlot(String, String, String)} adds a slot to {@code cls} with a question.</li><li>{@link
 * #addSlot(String, String, String, String)} adds a slot to {@code cls} with a question and a type ({@code null} for
 * any).</li><li>{@link #toInstance(String, String)} makes a class with no subclasses a value of its
 * superclass.</li><li>{@link #removeSlot(String, String)} removes a slot {@code cls} defines itself.</li><li>{@link
 * #source(String)} returns the {@code defclass} form {@code cls} has now.</li><li>{@link #fileOf(String)} returns the
 * file {@code cls} was first read from, or {@code null} if defined later.</li></ul>
 *
 * <p>Each instance operates in its own private Lisp package so that classes from different instances never see each
 * other. Classes and slots added later are defined in the same package, and every class below them sees them at once.
 * {@link #close()} removes the classes and the package.</p>
 */
public final class ConceptClasses implements AutoCloseable {

    /**
     * Represents a single property of a concept, containing its name, the class where it is defined, and the question
     * it answers.
     *
     * @param name the name of the slot
     * @param from the name of the class that defines this slot
     * @param question the question that this slot stands for
     */
    public record Slot(String name, String from, String question) {
    }

    /**
     * Represents a single concept class with its name and its documentation (what the concept is), which may be empty.
     *
     * @param name the name of the concept class
     * @param documentation the text describing the concept, which may be empty
     */
    public record Kind(String name, String documentation) {
    }

    private static final AtomicInteger INSTANCES = new AtomicInteger();

    /**
     * Contains the Lisp code that defines the package, functions, and class-manipulation logic used to build the
     * concept hierarchy.
     */
    private static final String SUPPORT = """
            (defpackage :rst-concept-classes (:use :cl))
            (in-package :rst-concept-classes)
            (defun own-class (pkg name)
              (let ((s (find-symbol (string-upcase name) pkg)))
                (and s (eq (symbol-package s) (find-package pkg)) (find-class s nil))))
            (defun class-names-in (pkg)
              (let (out)
                (do-symbols (s pkg) (when (and (eq (symbol-package s) (find-package pkg)) (find-class s nil)) (push s out)))
                (sort out #'string< :key #'symbol-name)))
            (defun lines (items) (format nil "~{~a~^~%~}" items))
            (defun doc-line (c) (substitute #\\Space #\\Newline (or (documentation c t) "")))
            (defun kind-line (c) (format nil "~(~a~)~a~a" (class-name c) #\\Tab (doc-line c)))
            (defun own-supers (pkg c)
              (remove-if-not (lambda (s) (eq (symbol-package (class-name s)) (find-package pkg))) (mop:class-direct-superclasses c)))
            (defun roots (pkg)
              (lines (loop for s in (class-names-in pkg) for c = (find-class s)
                           unless (own-supers pkg c) collect (kind-line c))))
            (defun subclasses (pkg name)
              (lines (mapcar #'kind-line (sort (copy-list (mop:class-direct-subclasses (own-class pkg name))) #'string<
                                               :key (lambda (c) (symbol-name (class-name c)))))))
            (defun chain (pkg name)
              (let ((c (own-class pkg name)))
                (mop:finalize-inheritance c)
                (lines (loop for k in (mop:class-precedence-list c)
                             when (eq (symbol-package (class-name k)) (find-package pkg)) collect (kind-line k)))))
            (defun what-can-be-said (pkg name)
              (let ((c (own-class pkg name)))
                (mop:finalize-inheritance c)
                (lines (loop for slot in (mop:class-slots c)
                             for s = (mop:slot-definition-name slot)
                             for from = (find-if (lambda (k) (find s (mop:class-direct-slots k) :key #'mop:slot-definition-name))
                                                 (mop:class-precedence-list c))
                             collect (format nil "~(~a~)~a~(~a~)~a~a" s #\\Tab (class-name from) #\\Tab
                                             (substitute #\\Space #\\Newline (or (documentation slot t) "")))))))
            (defun direct-slots (c)
              (loop for slot in (mop:class-direct-slots c)
                    collect (list (mop:slot-definition-name slot) (or (documentation slot t) "") (mop:slot-definition-type slot))))
            (defun redefine (pkg name supers slots doc)
              (eval `(defclass ,(intern (string-upcase name) pkg) ,(mapcar (lambda (s) (intern (string-upcase s) pkg)) supers)
                       ,(mapcar (lambda (s) (append (list (if (symbolp (first s)) (first s) (intern (string-upcase (first s)) pkg))
                                                          :documentation (second s))
                                                    (let ((type (third s)))
                                                      (cond ((or (null type) (eq type t) (equal type "")) nil)
                                                            ((symbolp type) (list :type type))
                                                            (t (list :type (intern (string-upcase type) pkg)))))))
                                slots)
                       ,@(when (plusp (length doc)) `((:documentation ,doc)))))
              (mapc #'mop:finalize-inheritance (mop:class-direct-subclasses (own-class pkg name)))
              name)
            (defun define-class (pkg name supers doc) (redefine pkg name supers nil doc))
            (defun add-slot (pkg name slot question type)
              (let ((c (own-class pkg name)))
                (redefine pkg name (mapcar (lambda (s) (string-downcase (class-name s))) (own-supers pkg c))
                          (append (direct-slots c) (list (list slot question type))) (or (documentation c t) ""))))
            (defun source (pkg name)
              (let ((c (own-class pkg name)) (*package* (find-package pkg)))
                (with-output-to-string (o)
                  (format o "(defclass ~(~a~) (~{~(~a~)~^ ~})" (class-name c) (mapcar #'class-name (own-supers pkg c)))
                  (let ((slots (direct-slots c)))
                    (if slots
                        (format o "~%  (~{~a~^~%   ~})"
                                (mapcar (lambda (s) (format nil "(~(~a~)~a :documentation ~s)" (first s)
                                                            (if (eq (third s) t) "" (format nil " :type ~(~a~)" (third s))) (second s)))
                                        slots))
                        (format o " ()")))
                  (when (documentation c t) (format o "~%  (:documentation ~s)" (documentation c t)))
                  (format o ")"))))
            (defun read-classes (pkg text)
              (let ((*package* (find-package pkg)))
                (with-input-from-string (in text)
                  (loop for form = (read in nil in) until (eq form in)
                        when (and (consp form) (symbolp (first form)) (string= (symbol-name (first form)) "DEFCLASS"))
                          do (eval (cons 'cl:defclass (rest form)))))))
            (defun all-classes (pkg) (lines (mapcar #'kind-line (mapcar #'find-class (class-names-in pkg)))))
            (defun superclasses (pkg name)
              (lines (mapcar (lambda (c) (string-downcase (class-name c))) (own-supers pkg (own-class pkg name)))))
            (defun remove-slot (pkg name slot)
              (let ((c (own-class pkg name)))
                (redefine pkg name (mapcar (lambda (s) (string-downcase (class-name s))) (own-supers pkg c))
                          (remove-if (lambda (s) (string-equal (symbol-name (first s)) slot)) (direct-slots c))
                          (or (documentation c t) ""))))
            (defun slot-lines (pkg name)
              (lines (mapcar (lambda (s) (format nil "~(~a~)~a~a~a~(~a~)" (first s) #\\Tab (substitute #\\Space #\\Newline (second s))
                                                 #\\Tab (if (eq (third s) t) "" (third s))))
                             (direct-slots (own-class pkg name)))))
            (defun remove-class (pkg name)
              (let ((c (own-class pkg name)))
                (dolist (s (mop:class-direct-superclasses c)) (mop:remove-direct-subclass s c))
                (setf (find-class (class-name c)) nil)
                name))
            (defun drop (pkg)
              (dolist (s (class-names-in pkg)) (setf (find-class s) nil))
              (delete-package pkg))
            """;

    private static Interpreter lisp;

    private final String pkg;
    /** The file path of the first file that provided each class name. */
    private final Map<String, String> files = new java.util.LinkedHashMap<>();
    /** Maps concept names to the name of the class they are an instance of. */
    private final Map<String, String> instances = new java.util.LinkedHashMap<>();
    private static final java.util.regex.Pattern INSTANCE =
            java.util.regex.Pattern.compile("(?m)^\\(instance \"((?:[^\"\\\\]++|\\\\.)*+)\" ([^\\s)]+)\\)");

    /**
     * Initializes a collection of concept classes from a set of files containing {@code defclass} forms.
     *
     * @param files a map where each key is a concept name and each value is the file path to the text containing its
     * {@code defclass} form.
     */
    public ConceptClasses(Map<String, String> files) {
        this.pkg = "RST-CONCEPTS-" + INSTANCES.incrementAndGet();
        synchronized (ConceptClasses.class) {
            if (lisp == null) {
                Interpreter in = Interpreter.createInstance();
                lisp = in != null ? in : Interpreter.getInstance();
                lisp.eval("(with-input-from-string (in " + string(SUPPORT) + ") (loop for f = (read in nil in) until (eq f in) do (eval f)))");
            }
        }
        // the package uses nothing, so that a concept named loop or values is a class of its own and not a Lisp symbol
        eval("(defpackage :" + pkg + " (:use))");
        for (Map.Entry<String, String> e : files.entrySet()) {
            java.util.Set<String> before = new java.util.HashSet<>(names());
            call("read-classes", string(e.getValue()));
            for (String n : names()) if (!before.contains(n)) this.files.putIfAbsent(n, e.getKey());
            java.util.regex.Matcher m = INSTANCE.matcher(e.getValue());
            while (m.find()) instances.put(m.group(1).replace("\\\"", "\""), m.group(2));
        }
    }

    /**
     * Represents one slot that a class defines itself: its name, the question it stands for, and the class its values
     * are of.
     *
     * @param name the name of the slot
     * @param question the question the slot's documentation answers
     * @param type the class of the slot's values, or an empty string for any type
     */
    public record OwnSlot(String name, String question, String type) {
    }

    /**
     * Returns a list of the slots that {@code cls} defines itself, excluding any inherited from superclasses. Each
     * {@link OwnSlot} contains the slot's name, the question it stands for, and the class its values are of.
     */
    public List<OwnSlot> ownSlots(String cls) {
        List<OwnSlot> out = new ArrayList<>();
        for (String l : call("slot-lines", string(cls)).split("\n")) {
            if (l.isEmpty()) continue;
            String[] f = l.split("\t", -1);
            out.add(new OwnSlot(f[0], f[1], f.length > 2 ? f[2] : ""));
        }
        return out;
    }

    /**
     * Removes a specific property from the definition of a concept.
     *
     * @param cls the name of the class
     * @param slot the name of the slot to remove
     */
    public void removeSlot(String cls, String slot) {
        call("remove-slot", string(cls), string(slot));
    }

    /**
     * Converts a concept class into a value of its superclass.
     *
     * <p>If {@code cls} has exactly one superclass and no subclasses, the class is removed and its name is kept as a
     * value of the superclass, so that {@link #find(String)} still finds the concept, now as the superclass.
     *
     * @param cls the name of the class to be converted
     * @param name the name to be kept as a value of the superclass
     * @throws IllegalStateException if {@code cls} has zero or more than one superclass, or if {@code cls} has any
     * subclasses
     */
    public void toInstance(String cls, String name) {
        List<String> supers = superclasses(cls);
        if (supers.size() != 1) throw new IllegalStateException(cls + " has " + supers.size() + " superclasses; only a class with one becomes its value");
        if (!subclasses(cls).isEmpty()) throw new IllegalStateException(cls + " has subclasses; only a class with none becomes a value");
        call("remove-class", string(cls));
        for (Map.Entry<String, String> e : instances.entrySet()) if (e.getValue().equals(cls)) e.setValue(supers.get(0));
        instances.put(name, supers.get(0));
    }

    /** Returns a map of concept names to the names of the classes they are values of. */
    public Map<String, String> instances() {
        return java.util.Collections.unmodifiableMap(instances);
    }

    /**
     * Returns a list of every concept class in the package, with each {@link Kind} containing the class name and its
     * documentation, sorted by name.
     */
    public List<Kind> all() {
        return kinds(call("all-classes"));
    }

    /** Returns a list of the names of every class. */
    public List<String> names() {
        return all().stream().map(Kind::name).toList();
    }

    /**
     * Returns a list of the names of the classes that {@code cls} is a direct subclass of.
     *
     * @param cls the name of the class to query
     */
    public List<String> superclasses(String cls) {
        List<String> out = new ArrayList<>();
        for (String l : call("superclasses", string(cls)).split("\n")) if (!l.isEmpty()) out.add(l);
        return out;
    }

    /**
     * Returns the path to the file from which the concept {@code cls} was first loaded, or {@code null} if the concept
     * was defined later in the same session.
     *
     * @param cls the name of the concept
     * @return the path to the source file, or {@code null} if the concept was not loaded from a file
     */
    public String fileOf(String cls) {
        return files.get(cls);
    }

    /** Removes the classes and the package created by this instance from the Lisp image. */
    @Override
    public void close() {
        call("drop");
    }

    /**
     * Returns the name of the class that the concept {@code name} belongs to, or {@code null} if the concept is not
     * found.
     *
     * If {@code name} is itself a class name, it returns {@code name}; otherwise, it returns the name of the class
     * that the concept is a value of.
     *
     * @param name the name of the concept to look up
     */
    public String find(String name) {
        String c = lispName(name);
        if (!c.isEmpty() && eval("(if (rst-concept-classes::own-class :" + pkg + " " + string(c) + ") \"y\" \"\")").equals("y")) return c;
        for (Map.Entry<String, String> e : instances.entrySet()) if (lispName(e.getKey()).equals(c)) return e.getValue();
        return null;
    }

    /**
     * Returns a list of the classes that have no superclasses among the classes held by this instance.
     *
     * @return a list of {@link Kind} objects representing the root classes
     */
    public List<Kind> roots() {
        return kinds(call("roots"));
    }

    /**
     * Returns a list of the classes that are direct subclasses of {@code cls}, each with its name and documentation.
     *
     * @param cls the name of the class whose direct subclasses are to be returned
     */
    public List<Kind> subclasses(String cls) {
        return kinds(call("subclasses", string(cls)));
    }

    /**
     * Returns a list of the concept {@code cls} and its superclasses, ordered from nearest to furthest, where each
     * entry contains the class name and its documentation.
     *
     * @param cls the name of the concept to trace up the hierarchy
     */
    public List<Kind> chain(String cls) {
        return kinds(call("chain", string(cls)));
    }

    /**
     * Returns a list of all slots available to {@code cls}, including those defined in its own class and those
     * inherited from its superclasses, ordered by the CLOS class precedence list.
     *
     * @param cls the name of the class to inspect
     * @return a list of {@link Slot} objects, where each slot contains its name, the class it was defined in, and the
     * question it stands for
     */
    public List<Slot> whatCanBeSaid(String cls) {
        List<Slot> out = new ArrayList<>();
        for (String l : call("what-can-be-said", string(cls)).split("\n")) {
            String[] f = l.split("\t", 3);
            if (f.length == 3) out.add(new Slot(f[0], f[1], f[2]));
        }
        return out;
    }

    /**
     * Defines a new concept class with no slots of its own, inheriting from {@code superclass} if provided.
     *
     * @param cls the name of the new class
     * @param superclass the name of the class to inherit from, or {@code null} if there is no superclass
     * @param documentation the text describing the concept, or {@code null} for no documentation
     */
    public void define(String cls, String superclass, String documentation) {
        call("define-class", string(cls), superclass == null ? "nil" : "(list " + string(superclass) + ")",
                string(documentation == null ? "" : documentation));
    }

    /**
     * Adds a slot to {@code cls} that every class under it inherits.
     *
     * @param cls the name of the class to modify
     * @param slot the name of the new slot
     * @param question the question the slot stands for
     */
    public void addSlot(String cls, String slot, String question) {
        addSlot(cls, slot, question, null);
    }

    /**
     * Adds a slot to {@code cls} that defines a property or part of the concept, with a question that a paragraph
     * about the concept must answer.
     *
     * @param cls the name of the class to modify
     * @param slot the name of the new slot
     * @param question the text of the question the slot represents
     * @param type the class of the slot's values, or {@code null} if it can be any type
     */
    public void addSlot(String cls, String slot, String question, String type) {
        call("add-slot", string(cls), string(slot), string(question), string(type == null ? "" : type));
    }

    /**
     * Returns a string containing the {@code defclass} form for the concept {@code cls}.
     *
     * @param cls the name of the concept whose definition is to be returned
     */
    public String source(String cls) {
        return call("source", string(cls));
    }

    /**
     * Converts a name into a Lisp-compatible identifier by splitting at case changes, converting to lower case, and
     * replacing non-alphanumeric sequences with single dashes.
     *
     * @param name the name to convert
     * @return a string where case changes and non-letters are replaced by dashes, and leading or trailing dashes are
     * removed
     */
    public static String lispName(String name) {
        String s = name.strip().replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
        return s.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    private String call(String fn, String... args) {
        return eval("(rst-concept-classes::" + fn + " :" + pkg + (args.length == 0 ? "" : " " + String.join(" ", args)) + ")");
    }

    private static final String FAILED = "rst-concept-classes failed: ";

    /**
     * Throws an {@link IllegalStateException} if the Lisp evaluation fails.
     *
     * <p>The exception's message contains the Lisp error message and a prefix of the form {@code rst-concept-classes
     * failed: } followed by the first 300 characters of the form. If the evaluation succeeds, it returns the result of
     * the form as a string.
     *
     * @param form the Lisp form to evaluate
     * @return the result of the evaluation as a string
     * @throws IllegalStateException if the Lisp evaluation results in an error
     */
    private static String eval(String form) {
        synchronized (ConceptClasses.class) {
            var r = lisp.eval("(handler-case " + form + " (error (e) (format nil \"" + FAILED + "~a\" e)))");
            String out = r instanceof org.armedbear.lisp.AbstractString ? r.getStringValue() : r.princToString();
            if (out.startsWith(FAILED)) throw new IllegalStateException(out + " in " + (form.length() > 300 ? form.substring(0, 300) + " ..." : form));
            return out;
        }
    }

    private static List<Kind> kinds(String text) {
        List<Kind> out = new ArrayList<>();
        for (String l : text.split("\n")) {
            if (l.isEmpty()) continue;
            String[] f = l.split("\t", 2);
            out.add(new Kind(f[0], f.length == 2 ? f[1] : ""));
        }
        return out;
    }

    /** Returns a string that is the input {@code s} escaped as a Lisp string literal. */
    static String string(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
