package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.armedbear.lisp.Interpreter;

/**
 * Concept classes held as CLOS classes in ABCL ({@code ConceptClasses_261022_oo01}): a concept is a class, kind-of is
 * a superclass, and what can be said of a concept is a slot whose documentation is the question a paragraph about the
 * concept answers. What a class inherits is computed by CLOS, not by this class.
 *
 * <p>The class files of {@code rst-on-lisp/concepts/} are read into a package of this instance's own, so that two
 * instances never see each other's classes. Only their {@code defclass} forms are evaluated: a class file is data, and
 * a file of assignments ({@link SlotAssignment#resultFile}) can be read as one too. Classes and slots added later are
 * defined in the same package, and every class below them sees them at once. {@link #close()} removes the classes and
 * the package.</p>
 */
public final class ConceptClasses implements AutoCloseable {

    /** One slot of a class: its name, the class it is defined in, and the question it stands for. */
    public record Slot(String name, String from, String question) {
    }

    /** One class: its name and its documentation (what the concept is), which may be empty. */
    public record Kind(String name, String documentation) {
    }

    private static final AtomicInteger INSTANCES = new AtomicInteger();

    /** Functions every instance calls, in a package of their own; each takes the package of the classes as its first argument. */
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
    /** For each class read from a file, the file it was read from. */
    private final Map<String, String> files = new java.util.LinkedHashMap<>();
    /**
     * The values of classes: a concept whose class said nothing more than its superclass, kept by name with the class
     * it is a value of ({@code (instance "effort level" reasoning-effort)} in a class file).
     */
    private final Map<String, String> instances = new java.util.LinkedHashMap<>();
    private static final java.util.regex.Pattern INSTANCE =
            java.util.regex.Pattern.compile("(?m)^\\(instance \"((?:[^\"\\\\]|\\\\.)*)\" ([^\\s)]+)\\)");

    /** @param files the class files to read, path to text; each holds {@code defclass} forms */
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

    /** One slot a class defines itself: its name, its question, and the class its values are of ({@code ""} for any). */
    public record OwnSlot(String name, String question, String type) {
    }

    /** @return the slots {@code cls} defines itself, without the ones it inherits */
    public List<OwnSlot> ownSlots(String cls) {
        List<OwnSlot> out = new ArrayList<>();
        for (String l : call("slot-lines", string(cls)).split("\n")) {
            if (l.isEmpty()) continue;
            String[] f = l.split("\t", -1);
            out.add(new OwnSlot(f[0], f[1], f.length > 2 ? f[2] : ""));
        }
        return out;
    }

    /** Removes a slot {@code cls} defines itself. */
    public void removeSlot(String cls, String slot) {
        call("remove-slot", string(cls), string(slot));
    }

    /**
     * Makes a class with no subclasses a value of its superclass: the class is removed, and its name is kept as a value
     * of the superclass, so that {@link #find} still finds the concept, now as the superclass.
     */
    public void toInstance(String cls, String name) {
        List<String> supers = superclasses(cls);
        if (supers.size() != 1) throw new IllegalStateException(cls + " has " + supers.size() + " superclasses; only a class with one becomes its value");
        if (!subclasses(cls).isEmpty()) throw new IllegalStateException(cls + " has subclasses; only a class with none becomes a value");
        call("remove-class", string(cls));
        for (Map.Entry<String, String> e : instances.entrySet()) if (e.getValue().equals(cls)) e.setValue(supers.get(0));
        instances.put(name, supers.get(0));
    }

    /** @return the values of classes, by name, with the class each is a value of */
    public Map<String, String> instances() {
        return java.util.Collections.unmodifiableMap(instances);
    }

    /** @return every class, with its documentation, in name order */
    public List<Kind> all() {
        return kinds(call("all-classes"));
    }

    /** @return the names of every class */
    public List<String> names() {
        return all().stream().map(Kind::name).toList();
    }

    /** @return the classes {@code cls} is a direct subclass of */
    public List<String> superclasses(String cls) {
        List<String> out = new ArrayList<>();
        for (String l : call("superclasses", string(cls)).split("\n")) if (!l.isEmpty()) out.add(l);
        return out;
    }

    /** @return the file {@code cls} was first read from, or {@code null} for a class defined later */
    public String fileOf(String cls) {
        return files.get(cls);
    }

    /** Removes these classes and their package from the Lisp image. */
    @Override
    public void close() {
        call("drop");
    }

    /** @return the class the concept {@code name} is, or {@code null}: {@code ResultMessage} is {@code result-message} */
    public String find(String name) {
        String c = lispName(name);
        if (!c.isEmpty() && eval("(if (rst-concept-classes::own-class :" + pkg + " " + string(c) + ") \"y\" \"\")").equals("y")) return c;
        for (Map.Entry<String, String> e : instances.entrySet()) if (lispName(e.getKey()).equals(c)) return e.getValue();
        return null;
    }

    /** @return the classes with no superclass among these classes */
    public List<Kind> roots() {
        return kinds(call("roots"));
    }

    /** @return the classes right under {@code cls} */
    public List<Kind> subclasses(String cls) {
        return kinds(call("subclasses", string(cls)));
    }

    /** @return {@code cls} and its superclasses, nearest first, each with its documentation */
    public List<Kind> chain(String cls) {
        return kinds(call("chain", string(cls)));
    }

    /** @return what can be said of {@code cls}: every slot it has, its own and the inherited, in CLOS order */
    public List<Slot> whatCanBeSaid(String cls) {
        List<Slot> out = new ArrayList<>();
        for (String l : call("what-can-be-said", string(cls)).split("\n")) {
            String[] f = l.split("\t", 3);
            if (f.length == 3) out.add(new Slot(f[0], f[1], f[2]));
        }
        return out;
    }

    /** Defines a class with no slots of its own under {@code superclass} ({@code null} for none). */
    public void define(String cls, String superclass, String documentation) {
        call("define-class", string(cls), superclass == null ? "nil" : "(list " + string(superclass) + ")",
                string(documentation == null ? "" : documentation));
    }

    /** Adds a slot to {@code cls}; every class under it inherits the slot. */
    public void addSlot(String cls, String slot, String question) {
        addSlot(cls, slot, question, null);
    }

    /**
     * Adds a slot whose values are of the class {@code type} ({@code null} for any): a part of the whole {@code cls}
     * is, such as the message kinds of an agent loop.
     */
    public void addSlot(String cls, String slot, String question, String type) {
        call("add-slot", string(cls), string(slot), string(question), string(type == null ? "" : type));
    }

    /** @return the {@code defclass} form {@code cls} has now */
    public String source(String cls) {
        return call("source", string(cls));
    }

    /** @return the class name for a concept name: words split at case changes and non-letters, lower case, joined with dashes */
    public static String lispName(String name) {
        String s = name.strip().replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
        return s.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    private String call(String fn, String... args) {
        return eval("(rst-concept-classes::" + fn + " :" + pkg + (args.length == 0 ? "" : " " + String.join(" ", args)) + ")");
    }

    private static final String FAILED = "rst-concept-classes failed: ";

    /**
     * Evaluates one form. A Lisp error comes back as an {@link IllegalStateException}: left alone, it would enter the
     * interactive debugger, which waits for input on the standard input of the whole process.
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

    /** @return {@code s} as a Lisp string literal */
    static String string(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
