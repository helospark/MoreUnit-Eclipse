/**
 * MoreUnit-Plugin for Eclipse V3.5.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the Eclipse Public License - v 1.0.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See Eclipse Public License for more details.
 */
package org.moreunit.extension.handler;

import java.util.List;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.TextElement;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PartInitException;
import org.moreunit.extensionpoints.IAddTestMethodContext;
import org.moreunit.log.LogHandler;

/**
 * The class <code>ModifyTestMethodParticipator</code> modifies test methods created by
 * MoreUnit. Second try, more sophisticated, with using JDT-AST.
 * <p>
 * <b>&copy; AG, D-49326 Melle 2010</b>
 * <p>
 * <dl>
 * <dt><b>Changes:</b></dt>
 * <dd>09.08.2010 Gro Handle the case, that a new test class is created, as well. Throw
 * Exceptions, Jump to test method after modification</dd>
 * <dd>20.09.2010 Gro Bug fixed: <a href=
 * "http://sourceforge.net/tracker/?func=detail&aid=3072086&group_id=156007&atid=798056"
 * >3072086</a></dd>
 * <dd>22.09.2010 Gro Bug fixed: <a href=
 * "http://sourceforge.net/tracker/?func=detail&aid=3072083&group_id=156007&atid=798056"
 * >3072083</a></dd>
 * <dd>30.09.2010 Gro Now jump works correctly</dd>
 * <dd>07.10.2010 Gro Error fixed: JavaDoc Link to method under test wrong, Interface
 * {@link ITestMethodParticipator} introduced</dd>
 * </dl>
 * <p>
 * @author Andreas Groll
 * @version 07.10.2010
 * @since 1.5
 */
public class ModifyTestMethodParticipator implements ITestMethodParticipator {

	/**
	 * Test method context.
	 */
	private IAddTestMethodContext context;

	/**
	 * Constructor for AddTestMethodParticipator.
	 */
	public ModifyTestMethodParticipator() {

		// Default-Contructor
	}

	/**
	 * {@inheritDoc}
	 */
	public synchronized void modifyTestMethod(final IAddTestMethodContext context) throws Exception {

		// Kontext merken
		this.context = context;

		// Inits
		IMethod testMethod = context.getTestMethod();
		IMethod methodUnderTest = context.getMethodUnderTest();
		TestType testType = getTestType(context);
		LogHandler.getInstance().handleInfoLog("Context: " + context);
		LogHandler.getInstance().handleInfoLog("TestType: " + testType);

		// Testmethode im Editor �ffnen, sonst funzt die AST-Modifikation nicht
		IEditorPart editorPart;
		if (testMethod != null) {
			editorPart = openMethodInEditor(context.getTestMethod());
		} else {
			editorPart = openCompilationUnitInEditor(context.getTestClass());
		}

		// Kompilationseinheit mit Quelle erstellen
		final ICompilationUnit compilationUnit = context.getTestClass();
		final String compilationSource = compilationUnit.getSource();
		final IDocument sourceDocument = new Document(compilationSource);

		// Erzeuge AST-Wurzel aus ICompilationUnit
		final ASTParser astParser = ASTParser.newParser(AST.JLS3);
		astParser.setKind(ASTParser.K_COMPILATION_UNIT);
		astParser.setSource(compilationUnit);
		final CompilationUnit astRoot = (CompilationUnit)astParser.createAST(null);

		// �nderungen ab jetzt aufzeichen
		astRoot.recordModifications();

		// Methode, oder Methoden modifizieren
		boolean changesDone = false;
		IMethod jumpToMethod = null;
		if (context.isNewTestClassCreated()) {
			IMethod[] methods = compilationUnit.findPrimaryType().getMethods();
			for (IMethod iMethod : methods) {
				if (iMethod.getElementName().startsWith("test")) {
					if (modifyMethod(astRoot, iMethod, testType)) {
						jumpToMethod = iMethod;
						changesDone = true;
					}
				}
			}
		} else {
			changesDone = modifyMethod(astRoot, testMethod, testType, methodUnderTest);
			jumpToMethod = testMethod;
		}

		// Haben wir �nderungen?
		if (!changesDone) {
			return;
		}

		// Import zuf�gen
		addImports(astRoot, testType);

		// �nderungen committen 
		TextEdit edits = astRoot.rewrite(sourceDocument, compilationUnit.getJavaProject().getOptions(true));
		edits.apply(sourceDocument);
		String newSource = sourceDocument.get();
		compilationUnit.getBuffer().setContents(newSource);

		// Konsistent machen
		if (!compilationUnit.isConsistent()) {
			LogHandler.getInstance().handleInfoLog("Make consistent " + compilationUnit.getElementName());
			compilationUnit.makeConsistent(null);
		}

		// Speichern
		if (compilationUnit.hasUnsavedChanges()) {
			LogHandler.getInstance().handleInfoLog("Save " + compilationUnit.getElementName());
			compilationUnit.save(null, true);
		}

		// Zur Methode springen, wenn m�glich
		if (jumpToMethod != null) {
			LogHandler.getInstance().handleInfoLog("Jump to " + jumpToMethod.getElementName());
			jumpToMethod(editorPart, jumpToMethod);
		}
	}

	/**
	 * Modify the test method. A method will not be changed, if it throws an exception.
	 * <p>
	 * As the method under test is not well known, no link to the method under test will
	 * be created in the JavaDoc.
	 * @param astRoot Rootnode.
	 * @param testMethod Test method.
	 * @param testType Test type.
	 * @return Method changed?
	 * @throws JavaModelException Error.
	 */
	private boolean modifyMethod(final CompilationUnit astRoot, final IMethod testMethod,
		final TestType testType) throws JavaModelException {

		return modifyMethod(astRoot, testMethod, testType, null);
	}

	/**
	 * Modify the test method. A method will not be changed, if it throws an exception.
	 * <p>
	 * A link to the method under test will be created in the JavaDoc.
	 * @param astRoot Rootnode.
	 * @param testMethod Test method.
	 * @param testType Test type.
	 * @param methodUnderTest Method to test.
	 * @return Method changed?
	 * @throws JavaModelException Error.
	 */
	private boolean modifyMethod(final CompilationUnit astRoot, final IMethod testMethod,
		final TestType testType, final IMethod methodUnderTest) throws JavaModelException {

		// Info
		LogHandler.getInstance().handleInfoLog("Modify: " + testMethod.getElementName());

		// Methodendeklaration beschaffen
		MethodDeclaration testMethodDeclaration = findMethodDeclaration(astRoot, testMethod);

		// Wenn bereits Fehler geworfen werden, wurde diese Methode bereits verarbeitet, da in den
		// automatisch und von MoreUnit generierten Methoden keine Exceptions deklariert werden
		if (testMethodDeclaration.thrownExceptions().size() > 0) {
			return false;
		}

		// Astknoten erstellen, der modifiziert werden soll
		AST astToModify = testMethodDeclaration.getAST();

		// Werfen aller Fehler erlauben
		rawListAdd(testMethodDeclaration.thrownExceptions(), astToModify.newSimpleName("Exception"));

		// JavaDoc erzeugen
		Javadoc javaDoc = astToModify.newJavadoc();

		// Beschreibungsfeld mit JavaDoc-Link
		TagElement tagElement = astToModify.newTagElement();
		rawListAdd(tagElement.fragments(),
			newTextElement(astToModify, getJavaDocCommentText(methodUnderTest)));
		rawListAdd(javaDoc.tags(), tagElement);

		// Doku f�r Werfen aller Fehler
		tagElement = astToModify.newTagElement();
		tagElement.setTagName(TagElement.TAG_THROWS);
		rawListAdd(tagElement.fragments(), astToModify.newSimpleName("Exception"));
		rawListAdd(tagElement.fragments(), newTextElement(astToModify, "Error."));
		rawListAdd(javaDoc.tags(), tagElement);

		// JavaDoc zuweisen
		testMethodDeclaration.setJavadoc(javaDoc);

		// Nur f�r TestNG die Annotation �ndern
		if (testType.equals(TestType.TestNG)) {
			// Alle Annotationen entfernen (nicht �ber Iterator, da Liste ge�ndert wird!)
			removeAnnotations(testMethodDeclaration);
			// Neue Annotation erzeugen
			rawListInsertFirst(testMethodDeclaration.modifiers(), newTestAnnotation(astToModify));
		}

		// Methode wurde ver�ndert
		return true;
	}

	/**
	 * Returns the MoreUnit test method type.
	 * @param context Context.
	 * @return Test method type.
	 */
	private TestType getTestType(final IAddTestMethodContext context) {

		ICompilationUnit cu = context.getClassUnderTest();
		String typeName = context.getPreferences().getTestType(cu.getJavaProject());
		return TestType.get(typeName);
	}

	/**
	 * Adds the imports with respect to test type.
	 * @param astRoot Root node to be modified.
	 * @param testType Test type.
	 */
	private void addImports(final CompilationUnit astRoot, final TestType testType) {

		// Import zuf�gen
		switch (testType) {
			case JUnit3:
				addImport(astRoot, "junit.framework.TestCase", false);
				break;
			case JUnit4:
				addImport(astRoot, "org.junit.Test", false);
				addImport(astRoot, "org.junit.Assert", false);
				addImport(astRoot, "org.junit.Assert.fail", true);
				break;
			case TestNG:
				addImport(astRoot, "org.testng.annotations.Test", false);
				addImport(astRoot, "org.testng.Assert", false);
				addImport(astRoot, "org.testng.Assert.fail", true);
				break;
			default:
				throw new RuntimeException("Unexpected enum value(TestType): " + testType);
		}
	}

	/**
	 * F�gt eine Importanweisung hinzu.
	 * @param astRoot Wurzel.
	 * @param classToImport Zu importierende Klasse.
	 * @param isStatic Statischer Import?
	 */
	private void addImport(final CompilationUnit astRoot, final String classToImport, final boolean isStatic) {

		// Import bereits vorhanden
		for (Object o : astRoot.imports()) {
			ImportDeclaration i = (ImportDeclaration)o;
			if (i.getName().toString().equals(classToImport)) {
				return;
			}
		}

		// Astknoten erstellen, der modifiziert werden soll
		AST astToModify = astRoot.getAST();

		// Importdeklaration f�r fail zuf�gen
		ImportDeclaration importDeclaration = astToModify.newImportDeclaration();
		importDeclaration.setStatic(isStatic);
		importDeclaration.setName(astToModify.newName(classToImport));
		rawListAdd(astRoot.imports(), importDeclaration);
	}

	/**
	 * Removes all annotation declarations.
	 * @param methodDeclaration Method declaration.
	 */
	private void removeAnnotations(final MethodDeclaration methodDeclaration) {

		List<?> modifiers = methodDeclaration.modifiers();
		for (int i = modifiers.size() - 1; i >= 0; i--) {
			Object obj = modifiers.get(i);
			if (obj instanceof Annotation) {
				modifiers.remove(obj);
			}
		}
	}

	/**
	 * Create a new Testannotation.
	 * @param ast Ast-node.
	 * @return Annotation.
	 */
	private Annotation newTestAnnotation(final AST ast) {

		// Stringwert erzeugen
		StringLiteral strg = ast.newStringLiteral();
		strg.setLiteralValue("Standard");

		// Zuweisung erzeugen
		Assignment assignment = ast.newAssignment();
		assignment.setLeftHandSide(ast.newSimpleName("groups"));
		assignment.setOperator(Assignment.Operator.ASSIGN);
		assignment.setRightHandSide(strg);

		// Annotation anlegen
		SingleMemberAnnotation annotation = ast.newSingleMemberAnnotation();
		annotation.setTypeName(ast.newSimpleName("Test"));
		annotation.setValue(assignment);

		// Liefern
		return annotation;
	}

	/**
	 * Get a text element from ast with text.
	 * @param ast AST-Node.
	 * @param text Text.
	 * @return TextElement.
	 */
	private TextElement newTextElement(final AST ast, final String text) {

		TextElement textElement = ast.newTextElement();
		textElement.setText(text);
		return textElement;
	}

	/**
	 * Create the Testmethod JavaDocComment.
	 * @param methodUnderTest Method to Test.
	 * @return JavaDocComment.
	 * @throws JavaModelException Error.
	 */
	private String getJavaDocCommentText(final IMethod methodUnderTest) throws JavaModelException {

		// Zu testende Methode nicht bekannt, mit Link auf Klasse arbeiten
		if (methodUnderTest == null) {
			return getClassJavaDocCommentText(context.getClassUnderTest());
		} else {
			// Zu testende Methode bekannt, mit Link auf zu testende Methode arbeiten
			return getMethodJavaDocCommentText(methodUnderTest);
		}
	}

	/**
	 * Return a JavaDoc comment with a link to the class under test.
	 * @param classUnderTest Class under test.
	 * @return JavaDoc comment text.
	 * @throws JavaModelException Error.
	 */
	private String getClassJavaDocCommentText(final ICompilationUnit classUnderTest)
		throws JavaModelException {

		String linkTarget = classUnderTest.findPrimaryType().getFullyQualifiedName();
		return "Test method for " + createJavaDocLink(linkTarget) + ".";
	}

	/**
	 * Return a JavaDoc comment with a link to the method under test.
	 * @param methodUnderTest Method under test.
	 * @return JavaDoc comment text.
	 */
	private String getMethodJavaDocCommentText(final IMethod methodUnderTest) {

		// Parameterliste erstellen
		StringBuilder parameterList = new StringBuilder();
		for (String parameter : methodUnderTest.getParameterTypes()) {
			if (parameterList.length() > 0) {
				parameterList.append(", ");
			}

			// Name zuf�gen, ohne Generics
			String name = Signature.toString(parameter);
			name = name.split("<")[0];
			parameterList.append(name);
		}

		// Text
		String linkTarget = methodUnderTest.getDeclaringType().getFullyQualifiedName() // +
			+ "#" // +
			+ methodUnderTest.getElementName() // +
			+ "(" + parameterList + ")";

		// Kommentar bauen und liefern
		return "Test method for " + createJavaDocLink(linkTarget) + ".";
	}

	/**
	 * Creates a JavaDoc-Link.
	 * @param target Link target.
	 * @return JavaDoc-Link.
	 */
	private String createJavaDocLink(final String target) {

		return "{@link " + target + "}";
	}

	/**
	 * Find a method declaration in a compilation unit.
	 * @param astRoot Root of AST.
	 * @param testMethod Testmethod.
	 * @return Method declaration, or <code>null</code> if not found.
	 */
	private MethodDeclaration findMethodDeclaration(final CompilationUnit astRoot, final IMethod testMethod) {

		// �bergabevariable erstellen (Feld, da Variable final sein muss!)
		final MethodDeclaration[] foundMethodDeclaration = new MethodDeclaration[1];
		foundMethodDeclaration[0] = null;

		// Visitor erstellen
		ASTVisitor astVisitor = new ASTVisitor() {

			@Override
			public boolean visit(final MethodDeclaration methodDeclaration) {

				if (methodDeclaration.getName().toString().equals(testMethod.getElementName())) {
					foundMethodDeclaration[0] = methodDeclaration;
				}

				// Wenn wir die Methode gefunden haben, keine weiteren Kinder untersuchen
				return foundMethodDeclaration[0] != null;
			}
		};

		// Visitor anwenden
		astRoot.accept(astVisitor);

		// Liefern
		return foundMethodDeclaration[0];
	}

	/**
	 * Open a java element in editor.
	 * @param method Methode.
	 * @return EditorPart.
	 * @throws JavaModelException Fehler.
	 * @throws PartInitException Fehler.
	 */
	private IEditorPart openMethodInEditor(final IMethod method) throws PartInitException, JavaModelException {

		return JavaUI.openInEditor(method.getDeclaringType().getParent());
	}

	/**
	 * Open a java element in editor.
	 * @param compilationUnit Kompilationseinheit.
	 * @return EditorPart.
	 * @throws JavaModelException Fehler.
	 * @throws PartInitException Fehler.
	 */
	private IEditorPart openCompilationUnitInEditor(final ICompilationUnit compilationUnit)
		throws PartInitException, JavaModelException {

		return JavaUI.openInEditor(compilationUnit.getPrimaryElement());
	}

	/**
	 * Jump to method.
	 * @param editorPart EditorPart.
	 * @param method Method.
	 */
	private void jumpToMethod(final IEditorPart editorPart, final IMethod method) {

		JavaUI.revealInEditor(editorPart, (IJavaElement)method);
	}

	/**
	 * Adds an element to a raw list.
	 * @param list Raw list.
	 * @param obj Element.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private void rawListAdd(final List list, final Object obj) {

		list.add(obj);
	}

	/**
	 * Inserts an element to a raw list at the first position.
	 * @param list Raw list.
	 * @param firstObj Element.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private void rawListInsertFirst(final List list, final Object firstObj) {

		// Original Listenl�nge merken
		int origSize = list.size();

		// Sonderfall: Liste ist leer
		if (origSize == 0) {
			list.add(firstObj);
			return;
		}

		// Die Liste hat nun mindestens ein Element
		int idx = 0;
		Object old = null;
		while (true) {

			// Liste ist durch
			if (idx == origSize) {
				list.add(old);
				break;
			}

			// Umkopieren
			Object idxObj = list.get(idx);
			if (idx == 0) {
				list.set(idx, firstObj);
			} else {
				list.set(idx, old);
			}

			// Index incrementieren
			idx++;
			old = idxObj;
		}
	}
}
